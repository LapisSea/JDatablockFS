package com.lapissea.cfs.tools.logging.session;

import com.lapissea.cfs.io.impl.IOFileData;
import com.lapissea.cfs.io.impl.MemoryData;
import com.lapissea.cfs.tools.logging.LoggedMemoryUtils;
import com.lapissea.util.LogUtil;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Random;

public final class SesRun{
	
	public static void main(String[] args) throws IOException{
		var file = new File("sessions.dfs");
		var mem  = LoggedMemoryUtils.newLoggedMemory("ses", LoggedMemoryUtils.createLoggerFromConfig());
		try{
			IOFileData.readInto(file, mem);
		}catch(FileNotFoundException e){ }
		
		try(var service = SessionService.of(mem)){
			var n = "ayy" + new Random().nextInt(3);
			LogUtil.println(n);
			try(var ses = service.openSession(n)){
				var data = MemoryData.builder().withOnWrite(ses).build();
				
				data.writeUTF(true, "Hello world!");
				data.writeUTF(true, "Hello world!");
				data.writeUTF(true, "Hello, this is different.");
				data.writeUTF(true, "Hello, this is also longer than the others.");
				data.writeUTF(true, "This is shorter.");

//				data.write(true, "Hello world - num: 0!".getBytes(UTF_8));
//				for(int i = 0; i<5; i++){
//					data.write(true, ("Hello world - num: " + i + "!").getBytes(UTF_8));
//				}
//
//				var            cl   = Cluster.init(data);
//				IOList<String> uhhh = cl.getRootProvider().request("uhhh", IOList.class, String.class);
//				uhhh.add("hi!");
//				uhhh.add("how do?");
//				uhhh.set(0, "uwu");
//				for(int i = 0; i<10; i++){
//					uhhh.add("hiiiii");
//				}
			}
		}
		Files.write(file.toPath(), mem.readAll());
	}
	
}
