package com.lapissea.dfs.io.impl;

import com.lapissea.dfs.io.IOTransactionBuffer.TransactionExport;
import com.lapissea.dfs.io.RandomIO;
import com.lapissea.iterableplus.OptionalPP;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.zip.CRC32C;

/**
 * <pre>
 * Frame layout
 * Header (24 bytes)
 *   magic           8   "JDBFSWA2"
 *   payloadLength   4   int
 *   payloadCRC      4   CRC32C over payload
 *   headerChecksum  4   CRC32C over the first 16 bytes
 *   headerComplement 4  ~headerChecksum
 *
 * Payload (payloadLength bytes)
 *   finalSize       8   long // resulting file size after replay
 *   writeCount      4   int
 *   repeat writeCount times:
 *     offset        8   long
 *     dataLength    4   int
 *     data          N   bytes
 *
 * Trailer (8 bytes)
 *   complete        8   "COMMITT1"
 * </pre>
 */
final class FileWriteAheadLog implements AutoCloseable{
	
	interface Replay{
		void apply(TransactionExport changes, long size) throws IOException;
	}
	
	private static final long MAGIC    = id("JDBFSWA2"); // checksummed header
	private static final long COMPLETE = id("COMMITT1");
	
	private static final int MAX_RECORD_BYTES = 256*1024*1024;
	
	private static long id(String s){
		long v = 0;
		for(int i = 0; i<8; i++){
			char c = s.charAt(i);
			v = (v<<8)|c;
		}
		return v;
	}
	
	
	private final FileChannel channel;
	
	static Path path(Path main) throws IOException{
		var canonical = main.toRealPath();
		return canonical.resolveSibling(canonical.getFileName() + ".wal");
	}
	
	static FileWriteAheadLog open(Path main, boolean readOnly, boolean enabled) throws IOException{
		var     path   = path(main);
		boolean exists = Files.exists(path);
		if(readOnly){
			if(exists && Files.size(path)>0){
				throw new IOException("Pending WAL recovery requires a writable open: " + path);
			}
			return null;
		}
		if(!enabled && !exists){
			return null;
		}
		var channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
		try{
			if(!exists){
				channel.force(true);
				// The sidecar name must survive a crash before main-file modifications are allowed.
				try(var directory = FileChannel.open(path.toAbsolutePath().getParent(), StandardOpenOption.READ)){
					directory.force(true);
				}
			}
			return new FileWriteAheadLog(channel);
		}catch(Throwable e){
			channel.close();
			throw e;
		}
	}
	
	FileWriteAheadLog(FileChannel channel){ this.channel = channel; }
	
	long size() throws IOException{
		return channel.size();
	}
	
	void append(TransactionExport changes, long size) throws IOException{
		if(size<0){
			throw new IOException("Invalid WAL final size");
		}
		
		long bytes = 12;
		for(var write : changes.writes()){
			if(write.ioOffset()<0 || write.dataLength()<0 || write.ioOffset()>size - write.dataLength()){
				throw new IOException("Invalid WAL write range");
			}
			bytes = Math.addExact(bytes, 12L + write.dataLength());
		}
		
		if(bytes>MAX_RECORD_BYTES){
			throw new IOException("WAL transaction exceeds " + MAX_RECORD_BYTES + " bytes");
		}
		
		var buffer = ByteBuffer.allocate((int)bytes);
		buffer.putLong(size).putInt(changes.writes().size());
		
		for(var write : changes.writes()){
			buffer.putLong(write.ioOffset()).putInt(write.dataLength());
			buffer.put(write.data(), write.dataOffset(), write.dataLength());
		}
		
		var payload = buffer.array();
		var crc     = new CRC32C();
		crc.update(payload);
		
		channel.position(channel.size());
		
		var header    = ByteBuffer.allocate(24).putLong(MAGIC).putInt(payload.length).putInt((int)crc.getValue());
		var headerCRC = new CRC32C();
		headerCRC.update(header.array(), 0, 16);
		
		int headerChecksum = (int)headerCRC.getValue();
		
		write(header.putInt(headerChecksum).putInt(~headerChecksum).flip());
		write(ByteBuffer.wrap(payload));
		write(ByteBuffer.allocate(8).putLong(COMPLETE).flip());
		
		channel.force(true);
	}
	
	void recover(Replay replay) throws IOException{
		channel.position(0);
		long remaining = channel.size();
		
		while(remaining>0){
			if(remaining<24){
				break; // Torn header at the append tail.
			}
			
			var header = ByteBuffer.allocate(16);
			read(header);
			long magic = header.getLong();
			if(magic != MAGIC){
				break;
			}
			
			var extra = ByteBuffer.allocate(8);
			read(extra);
			
			int headerChecksum = extra.getInt();
			int complement     = extra.getInt();
			var headerCRC      = new CRC32C();
			headerCRC.update(header.array());
			// Validate framing before trusting a possibly torn length field
			if(complement != ~headerChecksum || (int)headerCRC.getValue() != headerChecksum){
				break;
			}
			
			int length = header.getInt(), checksum = header.getInt();
			if(length<12 || length>MAX_RECORD_BYTES){
				break;
			}
			
			long frameBytes = 24L + length + 8;
			if(remaining<frameBytes){
				break;
			}
			
			var payload = ByteBuffer.allocate(length);
			read(payload);
			
			var trailer = ByteBuffer.allocate(8);
			read(trailer);
			
			if(trailer.getLong() != COMPLETE){
				if(remaining == frameBytes){
					break;
				}
				throw new IOException("Invalid WAL completion marker before the append tail");
			}
			
			var crc = new CRC32C();
			crc.update(payload.array());
			if((int)crc.getValue() != checksum){
				// An interrupted force can leave a full length frame whose sectors are incomplete
				if(remaining == frameBytes){
					break;
				}
				throw new IOException("WAL checksum mismatch before the append tail");
			}
			
			try(var in = new DataInputStream(new ByteArrayInputStream(payload.array()))){
				long size  = in.readLong();
				int  count = in.readInt();
				if(size<0 || count<0 || count>(length - 12)/12){
					throw new IOException("Invalid WAL batch");
				}
				
				var writes = new ArrayList<RandomIO.WriteChunk>(count);
				for(int i = 0; i<count; i++){
					long offset = in.readLong();
					int  len    = in.readInt();
					
					if(offset<0 || len<0 || len>in.available() || offset>size - len){
						throw new IOException("Invalid WAL write range");
					}
					
					writes.add(new RandomIO.WriteChunk(offset, in.readNBytes(len)));
				}
				
				if(in.available() != 0){
					throw new IOException("Trailing WAL batch data");
				}
				
				replay.apply(new TransactionExport(OptionalPP.of(size), writes), size);
			}
			
			remaining -= frameBytes;
		}
	}
	
	void clear() throws IOException{
		channel.truncate(0);
		channel.position(0);
		channel.force(true);
	}
	
	private void write(ByteBuffer buffer) throws IOException{
		while(buffer.hasRemaining()){
			channel.write(buffer);
		}
	}
	
	private void read(ByteBuffer buffer) throws IOException{
		while(buffer.hasRemaining()){
			if(channel.read(buffer)<0){
				throw new IOException("Unexpected WAL EOF");
			}
		}
		buffer.flip();
	}
	
	@Override
	public void close() throws IOException{
		channel.close();
	}
}
