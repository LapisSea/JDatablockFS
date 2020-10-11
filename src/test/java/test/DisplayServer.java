package test;

import com.lapissea.cfs.Config;
import com.lapissea.cfs.io.content.ContentInputStream;
import com.lapissea.cfs.io.content.ContentOutputStream;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.util.LogUtil;
import com.lapissea.util.function.UnsafeConsumer;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.*;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class DisplayServer implements DataLogger{
	
	enum Action{
		LOG,
		RESET,
		FINISH
	}
	
	private static DataLogger getRealLogger(){
		return new DisplayLWJGL();
	}
	
	public static void main(String[] args) throws IOException{
//		LogUtil.Init.attach(LogUtil.Init.USE_CALL_POS|LogUtil.Init.USE_CALL_THREAD|LogUtil.Init.USE_TABULATED_HEADER);
		
		DataLogger display=getRealLogger();
		
		ServerSocket server=new ServerSocket(666);
		
		LogUtil.println("started");
		
		while(true){
			try(Socket client=server.accept()){
				LogUtil.println("connected", client);
				var           os     =client.getOutputStream();
				ContentReader content=new ContentInputStream.Wrapp(new BufferedInputStream(client.getInputStream()));
				
				Executor exec=Executors.newSingleThreadExecutor();
				
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
							
							exec.execute(()->display.log(new MemFrame(bb, ids, stackTrace)));
						}
						case RESET -> {
							display.reset();
						}
						case FINISH -> {
							client.close();
							break run;
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
			
		}
		
	}
	
	final DataLogger proxy;
	
	public DisplayServer(){
		this(null);
	}
	
	public DisplayServer(String jarPath){
		DataLogger proxy;
		try{
			var socketMake=new Socket();
			try{
				socketMake.connect(new InetSocketAddress(InetAddress.getLocalHost(), 666), 100);
			}catch(SocketTimeoutException e){
				socketMake.close();
				if(jarPath==null) throw new IOException(e);
				var debugMode=Config.DEBUG_VALIDATION;
				var args     ="--illegal-access=deny --enable-preview -XX:+UseG1GC -XX:MaxHeapFreeRatio=10 -XX:MinHeapFreeRatio=1 -Xms50m -server -XX:+UseCompressedOops";
				Runtime.getRuntime().exec("java -jar "+(debugMode?"-ea ":"")+args+" \""+new File(jarPath).getAbsolutePath()+"\"");
				socketMake=new Socket();
				socketMake.connect(new InetSocketAddress(InetAddress.getLocalHost(), 666), 1000);
			}
			var socket=socketMake;
			
			LogUtil.println("connected", socket);
			var is    =socket.getInputStream();
			var writer=new ContentOutputStream.Wrapp(new BufferedOutputStream(socket.getOutputStream()));
			
			UnsafeConsumer<Action, IOException> sendAction=(Action a)->{
				writer.writeInt1(a.ordinal());
				writer.flush();
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
						writer.flush();
					}catch(IOException e){
						throw new RuntimeException(e);
					}
				}
				
				@Override
				public void reset(){
					try{
						sendAction.accept(Action.RESET);
					}catch(IOException e){
						e.printStackTrace();
					}
				}
				
				@Override
				public void finish(){
					try{
						sendAction.accept(Action.FINISH);
						is.read();
						socket.close();
					}catch(IOException e){
						e.printStackTrace();
					}
				}
			};
			
			Executor   exec=Executors.newSingleThreadExecutor();
			DataLogger p1  =proxy;
			proxy=new DataLogger(){
				@Override
				public void log(MemFrame frame){
					exec.execute(()->p1.log(frame));
				}
				
				@Override
				public void reset(){
					exec.execute(p1::reset);
				}
				
				@Override
				public void finish(){
					exec.execute(p1::finish);
				}
			};
			
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
