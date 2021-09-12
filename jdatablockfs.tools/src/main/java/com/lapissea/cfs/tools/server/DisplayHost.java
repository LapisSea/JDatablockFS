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

import static com.lapissea.cfs.tools.server.ServerCommons.*;
import static com.lapissea.util.LogUtil.Init.*;

public class DisplayHost{
	public static void main(String[] args) throws IOException{
		LogUtil.Init.attach(USE_CALL_POS|USE_TABULATED_HEADER);
		
		var config=LoggedMemoryUtils.readConfig();
		int port  =(int)config.getOrDefault("port", 666);
		
		ServerSocket server=new ServerSocket(port);
		
		DataLogger display=Arrays.asList(args).contains("lazy")?null:getLocalLoggerImpl();
		
		
		LogUtil.println("started");
		int sesCounter=1;
		while(true){
			ServerSocket sessionServer;
			String       sessionName;
			try(Socket client=server.accept()){
				sessionServer=new ServerSocket(0);
				var out=new DataOutputStream(client.getOutputStream());
				out.writeInt(sessionServer.getLocalPort());
				out.flush();
				var in=new DataInputStream(client.getInputStream());
				sessionName=in.readUTF();
			}catch(Exception e){
				e.printStackTrace();
				continue;
			}
			
			if(display==null) display=getLocalLoggerImpl();
			
			DataLogger.Session session=display.getSession(sessionName);
			new Thread(()->{
				try(Socket client=sessionServer.accept()){
					session(session, client);
				}catch(Exception e){
					e.printStackTrace();
				}finally{
					UtilL.closeSilenty(sessionServer);
				}
				System.gc();
			}, "Session: "+sesCounter+"/"+sessionName).start();
		}
		
	}
	private static void session(DataLogger.Session display, Socket client) throws IOException{
		LogUtil.println("connected", client);
		var objInput=new ObjectInputStream(client.getInputStream());
		var out     =client.getOutputStream();
		
		run:
		while(true){
			try{
				switch(Action.values()[objInput.readByte()]){
				case LOG -> display.log((MemFrame)objInput.readObject());
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
}
