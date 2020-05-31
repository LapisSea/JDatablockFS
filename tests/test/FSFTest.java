package test;

import com.lapissea.fsf.Renderer;
import com.lapissea.fsf.endpoint.IFileSystem;
import com.lapissea.util.LogUtil;
import com.lapissea.util.function.UnsafeRunnable;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Iterator;
import java.util.Objects;
import java.util.stream.Stream;

import static com.lapissea.util.UtilL.*;

class FSFTest{
	
	public static final long[] NO_IDS=new long[0];
	
	static{
//		LogUtil.Init.attach(0);
		LogUtil.Init.attach(LogUtil.Init.USE_CALL_POS|LogUtil.Init.USE_TABULATED_HEADER);
	}
	
	public static <T> Stream<T> stream(Iterator<T> iter){
		Objects.requireNonNull(iter);
		
		Object endFlag=new Object();
		return Stream.generate(()->{
			if(!iter.hasNext()) return endFlag;
			return iter.next();
		}).takeWhile(e->e!=endFlag).map(e->(T)e);
	}
	
	public static void main(String[] args){
		
		TestTemplate.run(()->new Renderer.Client(666), FSFTest::test);
//		TestTemplate.run(()->new Renderer.None(), FSFTest::test);
	}
	
	private static void test(IFileSystem<String> fs, UnsafeRunnable<IOException> snapshot) throws IOException{
		
		snapshot.run();
		
		var testFile=fs.createFile("test", 8);
		
		try(OutputStream os=testFile.write()){
			os.write("THIS WORKS!!!!".getBytes());
		}
		
		var s2=fs.getFile("test").readAllString();
		Assert(s2.equals("THIS WORKS!!!!"), s2);
		
		
		snapshot.run();
		
		var aaa=fs.createFile("aaaaaaa");
		try(OutputStream os=aaa.write()){
			os.write("THIS REALLY WORKS!!!!".getBytes());
		}
		snapshot.run();
		
		
		var s0=fs.getFile("aaaaaaa").readAllString();
		Assert(s0.equals("THIS REALLY WORKS!!!!"), s0);
		
		
		testFile.writeAll("THIS W".getBytes());
		snapshot.run();
		testFile.writeAll("THIS WORKS!!!!".getBytes());
		testFile.writeAll("THIS W".getBytes());
		testFile.writeAll("THIS WORKS!!!!".getBytes());
		testFile.writeAll("THIS W".getBytes());
		testFile.writeAll("this works????".getBytes());
		snapshot.run();
		fs.createFile("test3", 8).writeAll("12345678B".getBytes());
		snapshot.run();
		fs.createFile("test4", 8).writeAll("123456789".getBytes());
		snapshot.run();
		fs.createFile("a5", 8).writeAll("12345678".getBytes());
		snapshot.run();
		fs.getFile("test3").writeAll("12345678".getBytes());
		snapshot.run();
		fs.getFile("test4").writeAll("12345678".getBytes());
		snapshot.run();
		fs.getFile("test3").writeAll("Ur mum very gay. Like very GAY. Ur mum so gay she make the gay not gay.".getBytes());
		var s1=fs.getFile("test3").readAllString();
		Assert(s1.equals("Ur mum very gay. Like very GAY. Ur mum so gay she make the gay not gay."), s1);
		snapshot.run();
		
		fs.defragment();
		snapshot.run();
		LogUtil.println(fs.getFile("test3").readAllString());
	}
}
