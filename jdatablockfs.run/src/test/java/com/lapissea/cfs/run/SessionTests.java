package com.lapissea.cfs.run;

import com.lapissea.cfs.io.IOInterface;
import com.lapissea.cfs.io.impl.MemoryData;
import com.lapissea.cfs.tools.logging.LoggedMemoryUtils;
import com.lapissea.cfs.tools.logging.session.SessionService;
import com.lapissea.cfs.tools.logging.session.Sessions;
import com.lapissea.util.function.UnsafeConsumer;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.List;

import static org.testng.Assert.assertEquals;

public class SessionTests{
	
	@Test
	void smallSimple() throws IOException{
		var strs = List.of(
			"Hello world!",
			"Hello world!",
			"Hello, this is different.",
			"Hello, this is also longer than the others.",
			"This is shorter."
		);
		var sesName = "test";
		var res = do1Ses(sesName, data -> {
			for(var str : strs){
				data.writeUTF(true, str);
			}
		});
		
		var explorer = new Sessions.Explorer(res);
		var session  = explorer.getSession(sesName);
		
		for(int i = 0; i<strs.size(); i++){
			var frame    = session.getFrame(i);
			var expected = strs.get(i);
			
			String actual;
			try(var io = frame.getData().io()){
				actual = io.readUTF();
				if(io.remaining() != 0) continue;
			}
			assertEquals(actual, expected, "Fail on " + i);
		}
	}
	
	IOInterface do1Ses(String sesName, UnsafeConsumer<IOInterface, IOException> run) throws IOException{
		var mem = LoggedMemoryUtils.newLoggedMemory("ses", LoggedMemoryUtils.createLoggerFromConfig());
		try(var service = SessionService.of(mem)){
			try(var ses = service.openSession(sesName)){
				var data = MemoryData.builder().withOnWrite(ses).build();
				run.accept(data);
			}
		}
		return mem;
	}
	
}
