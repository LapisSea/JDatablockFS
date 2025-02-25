package com.lapissea.dfs.run;

import com.lapissea.dfs.io.IOInterfaces;
import com.lapissea.dfs.io.impl.MemoryData;
import com.lapissea.dfs.logging.Log;
import com.lapissea.dfs.tools.newlogger.DBLogConnection;
import com.lapissea.dfs.tools.newlogger.DBLogIngestServer;
import com.lapissea.dfs.tools.newlogger.FrameDB;
import com.lapissea.util.UtilL;
import org.testng.annotations.AfterTest;
import org.testng.annotations.BeforeTest;
import org.testng.annotations.Test;

import java.io.IOException;
import java.net.BindException;

import static org.assertj.core.api.Assertions.assertThat;

public class SessionTests{
	
	private DBLogIngestServer server;
	
	@BeforeTest
	public void startServer(){
		Log.info("Starting SessionTests");
		
		server = new DBLogIngestServer(() -> new FrameDB(IOInterfaces.ofMemory()));
		Thread.ofVirtual().start(() -> {
			try{
				server.start();
			}catch(BindException e){
				Log.warn("Server failed to start: {}#red", e);
			}catch(IOException e){
				if(e.getMessage().equals("Closed by interrupt")){
					return;
				}
				e.printStackTrace();
			}
		});
	}
	@AfterTest
	public void stopServer(){
		server.stop();
		UtilL.sleep(100);
	}
	
	@Test
	public void ipc() throws IOException{
		try(var remote = new DBLogConnection.OfRemote();
		    var ses = remote.openSession("ipc")){
			var mem = MemoryData.builder().withOnWrite(ses.getIOHook()).build();
			try(var io = mem.io()){
				io.write(new byte[]{1, 2, 3, 4});
				assertThat(ses.readLastFrame()).isEqualTo(mem.readAll());
				io.setPos(0).write(11);
				assertThat(ses.readLastFrame()).isEqualTo(mem.readAll());
				io.setPos(2).write(13);
				assertThat(ses.readLastFrame()).isEqualTo(mem.readAll());
				io.write(new byte[]{21, 22, 23, 24});
				assertThat(ses.readLastFrame()).isEqualTo(mem.readAll());
			}
		}
	}
	
	@Test
	public void useAfterClose() throws IOException{
		try(var remote = new DBLogConnection.OfRemote();
		    var ses = remote.openSession("useAfterClose")){
			var mem = MemoryData.builder().withOnWrite(ses.getIOHook()).build();
			try(var io = mem.io()){
				io.write(new byte[]{1, 2, 3, 4});
				ses.close();
				io.setPos(2).write(13);
			}
		}
	}
	
	@Test(expectedExceptions = IllegalStateException.class)
	public void sessionAfterClose() throws IOException{
		var remote = new DBLogConnection.OfRemote();
		remote.openSession("test");
		remote.close();
		remote.openSession("test2");
	}
	
	
}
