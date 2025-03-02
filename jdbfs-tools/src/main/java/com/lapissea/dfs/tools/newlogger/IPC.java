package com.lapissea.dfs.tools.newlogger;

import com.lapissea.dfs.io.bit.EnumUniverse;
import com.lapissea.dfs.io.content.ContentInputStream;
import com.lapissea.dfs.io.content.ContentOutputStream;
import com.lapissea.dfs.logging.Log;
import com.lapissea.dfs.objects.NumberSize;
import com.lapissea.dfs.utils.iterableplus.Iters;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.StringJoiner;
import java.util.function.LongConsumer;
import java.util.stream.LongStream;

import static java.nio.charset.StandardCharsets.UTF_8;

public final class IPC{
	
	public static final int TIMEOUT      = 500;
	public static final int DEFAULT_PORT = 56786;//No meaning, just a random number in the private port space
	
	public enum MSGConnection{
		NACK, ACK, END,
		CONNECT, REQUEST_SESSION
	}
	
	public enum MSGSession{
		NACK, ACK, END,
		FRAME_FULL, FRAME_DIFF, CLEAR,
		READ_FULL, READ_STATS
	}
	
	public record HandshakeRes(int sessionManagementPort){ }
	
	public static HandshakeRes clientHandshake(InetSocketAddress serverAddress) throws IOException{
		try(var socket = new Socket()){
			socket.connect(serverAddress, TIMEOUT);
			Log.trace("CLIENT: Connected to {}#yellow", serverAddress);
			
			var output = new DataOutputStream(socket.getOutputStream());
			writeEnum(output, MSGConnection.CONNECT, true);
			
			var input    = new DataInputStream(socket.getInputStream());
			var response = readEnum(input, MSGConnection.class);
			switch(response){
				case ACK -> {
					var newPort = readPortNum(input);
					Log.trace("CLIENT: Received session management port on {}#green", newPort);
					return new HandshakeRes(newPort);
				}
				case NACK -> throw new IOException("Handshake NACK!");
				default -> throw new IOException("Unexpected handshake response from server: " + response);
			}
		}
	}
	
	public static ServerSocket recieveHandshake(ServerSocket ss) throws IOException{
		Log.log("SERVER: Waiting for handshake...");
		try(var socket = ss.accept()){
			var output = new DataOutputStream(socket.getOutputStream());
			var input  = new DataInputStream(socket.getInputStream());
			
			Log.trace("SERVER: Waiting on init message on {}#yellow", socket);
			var response = readEnum(input, MSGConnection.class);
			if(response != MSGConnection.CONNECT){
				writeEnum(output, MSGConnection.NACK, true);
				Log.warn("SERVER: Unexpected handshake init message: {}#red", response);
				
				return null;
			}
			Log.trace("SERVER: Acknowledging connection on {}#yellow", socket);
			writeEnum(output, MSGConnection.ACK, true);
			
			var connectionSocket = new ServerSocket(0);
			writePortNum(output, connectionSocket.getLocalPort());
			
			Log.trace("SERVER: Opened session management port on {}#green", connectionSocket.getLocalPort());
			return connectionSocket;
		}
	}
	
	public static Socket requestSession(Socket commSocket, String name) throws IOException{
		var out = new DataOutputStream(commSocket.getOutputStream());
		
		writeEnum(out, MSGConnection.REQUEST_SESSION, false);
		writeString(out, name, true);
		
		var in  = new DataInputStream(commSocket.getInputStream());
		var res = readEnum(in, MSGConnection.class);
		if(res != MSGConnection.ACK){
			throw new IOException("Server did not acknowledge the session but sent: " + res);
		}
		var port = IPC.readPortNum(in);
		
		var socket = new Socket();
		socket.connect(new InetSocketAddress(commSocket.getInetAddress(), port));
		return socket;
	}
	
	record RangeSet(long[] startsSizes){
		
		public static RangeSet from(LongStream stream){
			final class Range{
				private long from, to;
				Range(long from, long to){
					this.from = from;
					this.to = to;
				}
			}
			var writes = new ArrayList<Range>();
			stream.sequential().forEach(new LongConsumer(){
				Range lastRange;
				@Override
				public void accept(long i){
					if(lastRange == null){
						lastRange = new Range(i, i + 1);
						writes.add(lastRange);
						return;
					}
					if(lastRange.to == i){
						lastRange.to++;
					}else{
						var im1 = i - 1;
						for(Range range : writes){
							if(range.to == i){
								lastRange = range;
								range.to++;
								return;
							}
							if(range.from == im1){
								lastRange = range;
								range.from--;
								return;
							}
						}
						
						lastRange = new Range(i, i + 1);
						writes.add(lastRange);
					}
				}
			});
			
			long[] startsSizes = new long[writes.size()*2];
			for(int i = 0; i<writes.size(); i++){
				var write = writes.get(i);
				startsSizes[i*2] = write.from;
				startsSizes[i*2 + 1] = write.to - write.from;
			}
			return new RangeSet(startsSizes);
		}
		
		RangeSet{
			if(startsSizes.length%2 != 0){
				throw new IllegalArgumentException("Array length should be even");
			}
		}
		@Override
		public String toString(){
			var joiner = new StringJoiner(", ", "[", "]");
			for(int i = 0; i<startsSizes.length; i += 2){
				var start = startsSizes[i];
				var size  = startsSizes[i + 1];
				if(size == 1) joiner.add(start + "");
				else joiner.add(start + "..." + (start + size));
			}
			return joiner.toString();
		}
	}
	
	public sealed interface SendFrame{
		long uid();
	}
	
	public record FullFrame(long uid, byte[] data, RangeSet writes) implements SendFrame{
		@Override
		public String toString(){
			return "FullFrame{" +
			       "uid=" + uid +
			       ", data=" + Arrays.toString(data) +
			       ", writes=" + writes +
			       '}';
		}
	}
	
	public record DiffPart(int offset, byte[] data){
		@Override
		public String toString(){
			return "DiffPart{" +
			       "offset=" + offset +
			       ", data=" + Arrays.toString(data) +
			       '}';
		}
	}
	
	public record DiffFrame(long uid, long prevUid, int newSize, DiffPart[] parts, RangeSet writes) implements SendFrame{
		@Override
		public String toString(){
			return "DiffFrame{" +
			       "uid=" + uid +
			       ", prevUid=" + prevUid +
			       (newSize == -1? "" : ", newSize=" + newSize) +
			       ", parts=" + Iters.of(parts).joinAsStr(", ", "[", "]", p -> "{" + p.offset + " -> " + Arrays.toString(p.data) + "}") +
			       ", writes=" + writes +
			       '}';
		}
	}
	
	public static void writeDiffFrame(DataOutputStream dest, DiffFrame frame) throws IOException{
		dest.writeLong(frame.uid);
		dest.writeLong(frame.prevUid);
		dest.writeInt(frame.newSize);
		dest.writeInt(frame.parts.length);
		for(DiffPart part : frame.parts){
			dest.writeInt(part.offset);
			writeBytes(dest, part.data, false);
		}
		writeLongs(dest, frame.writes.startsSizes);
	}
	
	public static DiffFrame readDiffFrame(DataInputStream src) throws IOException{
		var uid      = src.readLong();
		var prevUid  = src.readLong();
		var newSize  = src.readInt();
		var partsLen = src.readInt();
		var parts    = new DiffPart[partsLen];
		for(int i = 0; i<partsLen; i++){
			var offset = src.readInt();
			var bytes  = readBytes(src);
			parts[i] = new DiffPart(offset, bytes);
		}
		long[] ids = readLongs(src);
		return new DiffFrame(uid, prevUid, newSize, parts, new RangeSet(ids));
	}
	
	public static void writeStats(DataOutputStream dest, DBLogConnection.Session.SessionStats stats) throws IOException{
		dest.writeLong(stats.frameCount());
		dest.writeLong(stats.lastFrameUid());
	}
	
	public static DBLogConnection.Session.SessionStats readStats(DataInputStream src) throws IOException{
		var frameCount   = src.readLong();
		var lastFrameUid = src.readLong();
		return new DBLogConnection.Session.SessionStats(frameCount, lastFrameUid);
	}
	
	public static void writeFullFrame(DataOutputStream dest, FullFrame frame) throws IOException{
		dest.writeLong(frame.uid);
		writeBytes(dest, frame.data, false);
		writeLongs(dest, frame.writes.startsSizes);
	}
	
	public static FullFrame readFullFrame(DataInputStream src) throws IOException{
		var uid    = src.readLong();
		var data   = readBytes(src);
		var writes = readLongs(src);
		return new FullFrame(uid, data, new RangeSet(writes));
	}
	
	private static void writeLongs(DataOutputStream dest, long[] ids) throws IOException{
		long min = Long.MAX_VALUE, max = Long.MIN_VALUE;
		for(long id : ids){
			max = Math.max(max, id);
			min = Math.min(min, id);
		}
		var maxS = NumberSize.bySizeSigned(max);
		var minS = NumberSize.bySizeSigned(min);
		var size = maxS.max(minS);
		
		dest.writeInt(ids.length);
		writeEnum(dest, size, false);
		
		var d = new ContentOutputStream.Wrapp(dest);
		for(long id : ids){
			size.write(d, id);
		}
	}
	
	private static long[] readLongs(DataInputStream src) throws IOException{
		var idLen = src.readInt();
		var size  = readEnum(src, NumberSize.class);
		
		long[] ids = new long[idLen];
		var    s   = new ContentInputStream.Wrapp(src);
		for(int i = 0; i<idLen; i++){
			ids[i] = size.readSigned(s);
		}
		return ids;
	}
	
	public static int readPortNum(DataInputStream input) throws IOException{
		var newPort = input.readInt();
		if(newPort<=0 || newPort>65535){
			throw new IOException("Invalid port number: " + newPort);
		}
		return newPort;
	}
	public static void writePortNum(DataOutputStream output, int port) throws IOException{
		if(port<=0 || port>65535){
			throw new IOException("Invalid port number: " + port);
		}
		output.writeInt(port);
		output.flush();
	}
	
	public static <E extends Enum<E>> E readEnum(DataInputStream src, Class<E> type) throws IOException{
		var ordinal  = src.readShort();
		var universe = EnumUniverse.of(type);
		if(ordinal<0 || ordinal>=universe.size()){
			throw new IOException("Invalid enum ordinal: " + ordinal);
		}
		return universe.get(ordinal);
	}
	public static <E extends Enum<E>> void writeEnum(DataOutputStream dest, E val, boolean flush) throws IOException{
		dest.writeShort(val.ordinal());
		if(flush) dest.flush();
	}
	
	public static String readString(DataInputStream input) throws IOException{
		byte[] buff = readBytes(input);
		return new String(buff, UTF_8);
	}
	public static byte[] readBytes(DataInputStream input) throws IOException{
		int    length = input.readInt();
		byte[] buff   = new byte[length];
		input.readFully(buff);
		return buff;
	}
	
	public static void writeString(DataOutputStream output, String message, boolean flush) throws IOException{
		writeBytes(output, message.getBytes(UTF_8), flush);
	}
	public static void writeBytes(DataOutputStream output, byte[] messageBytes, boolean flush) throws IOException{
		output.writeInt(messageBytes.length);
		output.write(messageBytes);
		if(flush) output.flush();
	}
}
