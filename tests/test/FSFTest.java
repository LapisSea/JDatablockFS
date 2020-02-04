package test;

import com.lapissea.fsf.Renderer;
import com.lapissea.util.LogUtil;

import java.io.OutputStream;

import static com.lapissea.util.UtilL.*;

class FSFTest{
	
	public static final long[] NO_IDS=new long[0];
	
	static{
//		LogUtil.Init.attach(0);
		LogUtil.Init.attach(LogUtil.Init.USE_CALL_POS|LogUtil.Init.USE_TABULATED_HEADER);
	}
	
	public static void main(String[] args){
		TestTemplate.run(Renderer.GUI::new, (fil, snapshot)->{
			var testFile=fil.createFile("test", 8);
			try(OutputStream os=testFile.write()){
				os.write("THIS WORKS!!!!".getBytes());
			}
			
			var s2=fil.getFile("test").readAllString();
			Assert(s2.equals("THIS WORKS!!!!"), s2);
			
			snapshot.run();
			
			try(OutputStream os=fil.getFile("aaaaaaa").write()){
				os.write("THIS REALLY WORKS!!!!".getBytes());
			}
			snapshot.run();
			
			var s0=fil.getFile("aaaaaaa").readAllString();
			Assert(s0.equals("THIS REALLY WORKS!!!!"), s0);
			
			testFile.writeAll("THIS W".getBytes());
			snapshot.run();
			testFile.writeAll("THIS WORKS!!!!".getBytes());
			testFile.writeAll("THIS W".getBytes());
			testFile.writeAll("THIS WORKS!!!!".getBytes());
			testFile.writeAll("THIS W".getBytes());
			testFile.writeAll("this works????".getBytes());
			snapshot.run();
			fil.createFile("test3", 8).writeAll("12345678B".getBytes());
			snapshot.run();
			fil.createFile("test4", 8).writeAll("123456789".getBytes());
			snapshot.run();
			fil.createFile("a5", 8).writeAll("12345678".getBytes());
			snapshot.run();
			fil.getFile("test3").writeAll("12345678".getBytes());
			snapshot.run();
			fil.getFile("test4").writeAll("12345678".getBytes());
			snapshot.run();
			fil.getFile("test3").writeAll("Ur mum very gay. Like very GAY. Ur mum so gay she make the gay not gay.".getBytes());
			var s1=fil.getFile("test3").readAllString();
			Assert(s1.equals("Ur mum very gay. Like very GAY. Ur mum so gay she make the gay not gay."), s1);
			snapshot.run();
			
			
			fil.defragment();
			snapshot.run();
			LogUtil.println(fil.getFile("test3").readAllString());
			if(true) return;
			
			LogUtil.println(testFile.readAllString());
			
			fil.delete("");
			
			LogUtil.println(fil.getFile("test2").readAllString());
			
			fil.getFile("test").writeAll("This is a very very very fucking long text that will force the file to grow.".getBytes());
			LogUtil.println(fil.getFile("test").readAllString());
			
			fil.getFile("test").writeAll("This is a bit smaller text that will force the file to grow shrink.".getBytes());
			LogUtil.println(fil.getFile("test").readAllString());
			
			fil.getFile("test").writeAll("This is small.".getBytes());
			LogUtil.println(fil.getFile("test").readAllString());
			
			fil.delete("");
			System.exit(0);
			
			fil.delete("test");
			
			fil.getFile("test");
		});
	}
	
}
