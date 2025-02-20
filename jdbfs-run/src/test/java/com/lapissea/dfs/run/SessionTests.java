package com.lapissea.dfs.run;

import com.lapissea.dfs.io.impl.MemoryData;
import com.lapissea.dfs.logging.Log;
import com.lapissea.dfs.tools.newlogger.DBLogConnection;
import com.lapissea.dfs.tools.newlogger.DBLogServer;
import com.lapissea.util.UtilL;
import org.testng.Assert;
import org.testng.annotations.AfterTest;
import org.testng.annotations.BeforeTest;
import org.testng.annotations.Test;

import java.io.IOException;

public class SessionTests{
	
	private DBLogServer server;
	
	@BeforeTest
	public void startServer(){
		Log.info("Starting SessionTests");
		
		server = new DBLogServer();
		Thread.ofVirtual().start(() -> {
			try{
				server.run();
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
				Assert.assertEquals(ses.readLastFrame(), mem.readAll());
				io.setPos(0).write(11);
				Assert.assertEquals(ses.readLastFrame(), mem.readAll());
				io.setPos(2).write(13);
				Assert.assertEquals(ses.readLastFrame(), mem.readAll());
				io.write(new byte[]{21, 22, 23, 24});
				Assert.assertEquals(ses.readLastFrame(), mem.readAll());
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
