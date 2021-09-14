package com.lapissea.cfs.tools.server;

import com.lapissea.cfs.tools.logging.DataLogger;
import com.lapissea.cfs.tools.logging.LoggedMemoryUtils;
import com.lapissea.cfs.tools.logging.MemFrame;
import com.lapissea.util.LogUtil;
import com.lapissea.util.UtilL;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import static com.lapissea.cfs.tools.server.ServerCommons.*;
import static com.lapissea.util.LogUtil.Init.*;
import static com.lapissea.util.PoolOwnThread.*;

public class DisplayHost{
	public static void main(String[] args) throws IOException{
		LogUtil.Init.attach(USE_CALL_POS|USE_TABULATED_HEADER);
		
		new DisplayHost().start(Arrays.asList(args).contains("lazy"));
	}
	
	private void session(String name, Socket client) throws IOException{
		LogUtil.println("connected", client);
		var objInput=new ObjectInputStream(client.getInputStream());
		var out     =client.getOutputStream();
		
		Supplier<DataLogger.Session> ses=()->getDisplay().join().getSession(name);
		
		run:
		while(true){
			try{
				switch(Action.values()[objInput.readByte()]){
				case LOG -> ses.get().log((MemFrame)objInput.readObject());
				case RESET -> {
					if(display!=null){
						ses.get().reset();
						System.gc();
					}
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
				if("Connection reset".equals(e.getMessage())){
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
	}
	
	private CompletableFuture<DataLogger> display;
	
	private record NegotiatedSession(ServerSocket sessionServer, String sessionName) implements AutoCloseable{
		static NegotiatedSession negotiate(ServerSocket source) throws IOException{
			try(Socket client=source.accept()){
				var sessionServer=new ServerSocket(0);
				var out          =new DataOutputStream(client.getOutputStream());
				out.writeInt(sessionServer.getLocalPort());
				out.flush();
				var in         =new DataInputStream(client.getInputStream());
				var sessionName=in.readUTF();
				return new NegotiatedSession(sessionServer, sessionName);
			}
		}
		
		Socket open() throws IOException{
			return sessionServer.accept();
		}
		
		@Override
		public void close(){
			UtilL.closeSilenty(sessionServer);
		}
	}
	
	public void start(boolean lazyStart) throws IOException{
		var config=LoggedMemoryUtils.readConfig();
		int port  =((Number)config.getOrDefault("port", 666)).intValue();
		
		ServerSocket server=new ServerSocket(port);
		LogUtil.println("Started on port", port);
		
		if(!lazyStart) getDisplay();
		
		int sesCounter=1;
		while(true){
			var ses=NegotiatedSession.negotiate(server);
			
			new Thread(()->{
				try(ses;var client=ses.open()){
					session(ses.sessionName, client);
				}catch(Exception e){
					e.printStackTrace();
				}
				System.gc();
			}, "Session: "+sesCounter+"/"+ses.sessionName).start();
		}
	}
	
	public synchronized CompletableFuture<DataLogger> getDisplay(){
		if(display==null) display=async(ServerCommons::getLocalLoggerImpl);
		return display;
	}
}
