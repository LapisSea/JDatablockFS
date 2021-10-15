package com.lapissea.cfs.tools.server;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.lapissea.cfs.tools.Display2D;
import com.lapissea.cfs.tools.DisplayLWJGL;
import com.lapissea.cfs.tools.logging.DataLogger;
import com.lapissea.cfs.tools.logging.MemFrame;
import com.lapissea.util.function.UnsafeConsumer;

import java.io.*;
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
		try{
			return new DisplayLWJGL();
		}catch(Throwable e){
			e.printStackTrace();
			return new Display2D();
		}
	}
	
	static byte[] readSafe(DataInputStream in) throws IOException{
		var siz=in.readInt();
		return in.readNBytes(siz);
	}
	static void writeSafe(DataOutputStream dest, UnsafeConsumer<DataOutputStream, IOException> ses) throws IOException{
		ByteArrayOutputStream buff=new ByteArrayOutputStream();
		
		ses.accept(new DataOutputStream(buff));
		
		
		dest.writeInt(buff.size());
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
	
	private static ObjectIO dummyIO(){
		
		return new ObjectIO(){
			@Override
			public MemFrame readFrame(DataInputStream stream) throws IOException{
				readSafe(stream);
				return new MemFrame(new byte[8], new long[0], new Throwable());
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
				var big=stream.readBoolean();
				
				InputStream uncompressed=stream;
				if(big){
					uncompressed=new GZIPInputStream(uncompressed, 2048);
				}
				return io.readFrame(new DataInputStream(uncompressed));
			}
			@Override
			public void writeFrame(DataOutputStream stream, MemFrame frame) throws IOException{
				boolean big=(frame.ids().length*8+frame.data().length)>8192;
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
		var io=kryoIO();
		io=compressed(io);
		return io;
	}
}
