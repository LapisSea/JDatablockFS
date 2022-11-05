package com.lapissea.cfs.tools.server;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.lapissea.cfs.tools.DisplayManager;
import com.lapissea.cfs.tools.logging.DataLogger;
import com.lapissea.cfs.tools.logging.MemFrame;
import com.lapissea.util.function.UnsafeConsumer;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

class ServerCommons{
	
	interface ObjectIO{
		MemFrame readFrame(DataInputStream stream) throws IOException;
		void writeFrame(DataOutputStream stream, MemFrame frame) throws IOException;
	}
	
	enum Action{
		LOG,
		RESET,
		DELETE,
		FINISH,
		PING
	}
	
	static synchronized DataLogger getLocalLoggerImpl(){
		return new DisplayManager();
	}
	
	static byte[] readSafe(DataInputStream in) throws IOException{
		var siz=in.readInt();
		return in.readNBytes(siz);
	}
	static void writeSafe(OutputStream dest, UnsafeConsumer<DataOutputStream, IOException> ses) throws IOException{
		ByteArrayOutputStream buff=new ByteArrayOutputStream();
		
		ses.accept(new DataOutputStream(buff));
		
		int    v          =buff.size();
		byte[] writeBuffer=new byte[4];
		writeBuffer[0]=(byte)(v >>> 24);
		writeBuffer[1]=(byte)(v >>> 16);
		writeBuffer[2]=(byte)(v >>> 8);
		writeBuffer[3]=(byte)(v >>> 0);
		dest.write(writeBuffer, 0, 4);
		
		buff.writeTo(dest);
	}
	
	private static ObjectIO kryoIO(){
		
		return new ObjectIO(){
			private final ThreadLocal<Kryo> kryo=ThreadLocal.withInitial(()->{
				var k=new Kryo();
				k.register(MemFrame.class);
				k.register(byte[].class);
				k.register(long[].class);
				return k;
			});
			
			private Kryo kryo(){
				return kryo.get();
			}
			
			@Override
			public MemFrame readFrame(DataInputStream stream){
				return kryo().readObject(new Input(stream), MemFrame.class);
			}
			@Override
			public void writeFrame(DataOutputStream stream, MemFrame frame){
				var io=new Output(stream);
				kryo().writeObject(io, frame);
				io.flush();
			}
		};
	}
	private static ObjectIO serilizeIO(){
		
		return new ObjectIO(){
			@Override
			public MemFrame readFrame(DataInputStream stream) throws IOException{
				var in=new ObjectInputStream(new ByteArrayInputStream(readSafe(stream)));
				try{
					return (MemFrame)in.readObject();
				}catch(ClassNotFoundException e){
					throw new RuntimeException(e);
				}
			}
			@Override
			public void writeFrame(DataOutputStream stream, MemFrame frame) throws IOException{
				writeSafe(stream, buff->{
					var out=new ObjectOutputStream(buff);
					out.writeObject(frame);
				});
			}
		};
	}
	private static ObjectIO manualIO(){
		return new ObjectIO(){
			@Override
			public MemFrame readFrame(DataInputStream stream) throws IOException{
				long frameId  =stream.readLong();
				long timeDelta=stream.readLong();
				var  bytes    =readSafe(stream);
				var  idBuffer =ByteBuffer.wrap(readSafe(stream)).asLongBuffer();
				var  ids      =new long[idBuffer.limit()];
				idBuffer.get(ids);
				var e=new String(readSafe(stream), StandardCharsets.UTF_8);
				
				return new MemFrame(frameId, timeDelta, bytes, ids, e);
			}
			
			@Override
			public void writeFrame(DataOutputStream stream, MemFrame frame) throws IOException{
				stream.writeLong(frame.frameId());
				stream.writeLong(frame.timeDelta());
				writeSafe(stream, b->b.write(frame.bytes()));
				writeSafe(stream, b->{
					var data=new byte[frame.ids().length*Long.BYTES];
					ByteBuffer.wrap(data).asLongBuffer().put(frame.ids());
					b.write(data);
				});
				writeSafe(stream, b->b.write(frame.e().getBytes(StandardCharsets.UTF_8)));
				
			}
		};
	}
	
	private static ObjectIO dummyIO(){
		
		return new ObjectIO(){
			@Override
			public MemFrame readFrame(DataInputStream stream) throws IOException{
				readSafe(stream);
				return new MemFrame(-1, -1, new byte[8], new long[0], new Throwable());
			}
			@Override
			public void writeFrame(DataOutputStream stream, MemFrame frame) throws IOException{
				writeSafe(stream, buff->{
				});
			}
		};
	}
	
	private static ObjectIO compressed(ObjectIO io){
		
		return new ObjectIO(){
			
			@Override
			public MemFrame readFrame(DataInputStream stream) throws IOException{
				var compressFlag=stream.readBoolean();
				
				InputStream uncompressed=stream;
				if(compressFlag){
					uncompressed=new GZIPInputStream(uncompressed, 2048);
				}
				return io.readFrame(new DataInputStream(uncompressed));
			}
			@Override
			public void writeFrame(DataOutputStream stream, MemFrame frame) throws IOException{
				boolean big=frame.askForCompress;
				stream.writeBoolean(big);
				if(big){
					try(var z=new GZIPOutputStream(stream, 2048)){
						io.writeFrame(new DataOutputStream(z), frame);
					}
				}else io.writeFrame(stream, frame);
			}
		};
	}
	
	static ObjectIO makeIO(){
		var io=manualIO();
		io=compressed(io);
		return io;
	}
}
