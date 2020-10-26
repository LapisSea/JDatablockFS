package com.lapissea.cfs.tools;

import com.lapissea.cfs.GlobalConfig;
import com.lapissea.cfs.io.content.ContentInputStream;
import com.lapissea.cfs.io.content.ContentOutputStream;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.util.LogUtil;
import com.lapissea.util.function.UnsafeConsumer;
import com.lapissea.util.function.UnsafeRunnable;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.net.*;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SuppressWarnings("AutoBoxing")
public class DisplayServer implements DataLogger{
	
	enum Action{
		LOG,
		RESET,
		FINISH,
		PING
	}
	
	private static DataLogger getRealLogger(){
		try{
			return new DisplayLWJGL();
		}catch(Throwable e){
			return new Display2D();
		}
	}
	
	public static void main(String[] args) throws IOException{
		
		ServerSocket server=new ServerSocket(666);
		
		DataLogger display=Arrays.asList(args).contains("lazy")?null:getRealLogger();
		
		
		LogUtil.println("started");
		
		while(true){
			try(Socket client=server.accept()){
				LogUtil.println("connected", client);
				ContentReader content=new ContentInputStream.Wrapp(new BufferedInputStream(client.getInputStream()));
				var           out    =client.getOutputStream();
				if(display==null) display=getRealLogger();
				
				run:
				while(true){
					try{
						switch(Action.values()[content.readInt1()]){
						case LOG -> {
							byte[] bb =content.readInts1(content.readInt4());
							long[] ids=content.readInts8(content.readInt4());
							
							String[] stackTrace=new String[content.readInt4()];
							for(int i=0;i<stackTrace.length;i++){
								char[] data=content.readChars2(content.readInt4());
								stackTrace[i]=new String(data);
							}
							
							display.log(new MemFrame(bb, ids, stackTrace));
						}
						case RESET -> {
							display.reset();
							System.gc();
						}
						case FINISH -> {
							client.close();
							break run;
						}
						case PING -> {
							out.write(2);
							out.flush();
						}
						}
					}catch(SocketException e){
						if(e.getMessage().equals("Connection reset")){
							LogUtil.println("disconnected");
							break;
						}
						e.printStackTrace();
						break;
					}catch(Exception e){
						e.printStackTrace();
						break;
					}
				}
			}catch(Exception e){
				e.printStackTrace();
			}
			System.gc();
		}
		
	}
	
	final DataLogger proxy;
	
	public DisplayServer(Map<String, Object> config){
		final boolean threadedOutput=Boolean.parseBoolean(config.getOrDefault("threadedOutput", "false").toString());
		DataLogger    proxy;
		try{
			var socketMake=new Socket();
			try{
				socketMake.connect(new InetSocketAddress(InetAddress.getLocalHost(), 666), 100);
			}catch(SocketTimeoutException e){
				String jarPath=config.getOrDefault("jar", "").toString();
				socketMake.close();
				if(jarPath.isEmpty()) throw new IOException(e);
				var debugMode=GlobalConfig.DEBUG_VALIDATION;
				var args     ="--illegal-access=deny --enable-preview -XX:+UseG1GC -XX:MaxHeapFreeRatio=10 -XX:MinHeapFreeRatio=1 -Xms50m -server -XX:+UseCompressedOops";
				
				Process p=Runtime.getRuntime().exec("java -jar "+(debugMode?"-ea ":"")+args+" \""+new File(jarPath).getAbsolutePath()+"\" lazy");
				p.getInputStream().read();
				
				socketMake=new Socket();
				socketMake.connect(new InetSocketAddress(InetAddress.getLocalHost(), 666), 100);
			}
			var socket=socketMake;
			
			LogUtil.println("connected", socket);
			var is    =socket.getInputStream();
			var writer=new ContentOutputStream.Wrapp((socket.getOutputStream()));
//			var writer=new ContentOutputStream.Wrapp(new BufferedOutputStream(socket.getOutputStream()));
			
			UnsafeConsumer<Action, IOException> sendAction=(Action a)->writer.writeInt1(a.ordinal());
			
			UnsafeRunnable<IOException> flush=()->{
				if(threadedOutput) return;
//				sendAction.accept(Action.PING);
				writer.flush();
//				is.read();
			};
			
			proxy=new DataLogger(){
				@Override
				public void log(MemFrame frame){
					try{
						sendAction.accept(Action.LOG);
						
						writer.writeInt4(frame.data().length);
						writer.writeInts1(frame.data());
						writer.writeInt4(frame.ids().length);
						writer.writeInts8(frame.ids());
						writer.writeInt4(frame.e().length);
						for(String s : frame.e()){
							writer.writeInt4(s.length());
							writer.writeChars2(s);
						}
						flush.run();
					}catch(IOException e){
						throw new RuntimeException(e);
					}
				}
				
				@Override
				public void reset(){
					try{
						sendAction.accept(Action.RESET);
						flush.run();
					}catch(IOException e){
						e.printStackTrace();
					}
				}
				
				@Override
				public void finish(){
					try{
						sendAction.accept(Action.FINISH);
						writer.flush();
						is.read();
						socket.close();
					}catch(IOException e){
						e.printStackTrace();
					}
				}
			};
			
			if(threadedOutput){
				ExecutorService exec  =Executors.newSingleThreadExecutor();
				DataLogger      logger=proxy;
				proxy=new DataLogger(){
					@Override
					public void log(MemFrame frame){
						exec.execute(()->logger.log(frame));
					}
					
					@Override
					public void reset(){
						exec.execute(logger::reset);
					}
					
					@Override
					public void finish(){
						exec.execute(logger::finish);
						exec.shutdown();
					}
				};
			}
			
			proxy.reset();
		}catch(IOException e){
			e.printStackTrace();
			proxy=getRealLogger();
		}
		this.proxy=proxy;
	}
	
	@Override
	public void log(MemFrame frame){
		proxy.log(frame);
	}
	
	@Override
	public void finish(){
		proxy.finish();
	}
	
	@Override
	public void reset(){
		proxy.reset();
	}
}
