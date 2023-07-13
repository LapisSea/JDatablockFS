package com.lapissea.cfs.tools.logging.session;

import com.lapissea.cfs.io.impl.MemoryData;
import com.lapissea.util.LogUtil;

import java.io.File;
import java.io.IOException;
import java.util.Random;

import static java.nio.charset.StandardCharsets.UTF_8;

public class SesRun{
	
	
	public static void main(String[] args) throws IOException{
		try(var service = SessionService.of(new File("sessions.dfs"))){
			var n = "ayy" + new Random().nextInt(3);
			LogUtil.println(n);
			try(var ses = service.openSession(n)){
				var data = MemoryData.builder().withOnWrite(ses).build();
				
				data.write(true, "Hello world!".getBytes(UTF_8));
				data.write(true, "Hello world!".getBytes(UTF_8));
				data.write(true, "Hello, this is different.".getBytes(UTF_8));
				data.write(true, "Hello, this is also longer than the others.".getBytes(UTF_8));
				data.write(true, "This is shorter.".getBytes(UTF_8));

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
			}catch(Throwable e){
				e.printStackTrace();
				System.exit(1);
			}
		}
	}
	
}
