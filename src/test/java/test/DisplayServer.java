package test;

import com.lapissea.cfs.io.content.ContentInputStream;
import com.lapissea.cfs.io.content.ContentOutputStream;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.util.AsynchronousBufferingInputStream;
import com.lapissea.util.LogUtil;
import com.lapissea.util.function.UnsafeConsumer;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.*;
import java.util.concurrent.CompletableFuture;

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
		LogUtil.Init.attach(LogUtil.Init.USE_CALL_POS|LogUtil.Init.USE_CALL_THREAD|LogUtil.Init.USE_TABULATED_HEADER);
		
		DataLogger display=getRealLogger();
		
		ServerSocket server=new ServerSocket(666);
		
		while(true){
			try(Socket client=server.accept()){
				LogUtil.println("connected", client);
				var           os     =client.getOutputStream();
				ContentReader content=new ContentInputStream.Wrapp(AsynchronousBufferingInputStream.makeAsync(client.getInputStream()));
				
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
	CompletableFuture<Void> task=CompletableFuture.completedFuture(null);
	
	
	public DisplayServer(){
		DataLogger proxy;
		try{
			var socket=new Socket();
			socket.connect(new InetSocketAddress(InetAddress.getLocalHost(), 666), 100);
			
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
	public void reset(){
		proxy.reset();
	}
}
