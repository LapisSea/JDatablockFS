import com.lapissea.fsf.FileSystemInFile;
import com.lapissea.util.LogUtil;
import com.lapissea.util.function.UnsafeRunnable;

import javax.imageio.ImageIO;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;

import static com.lapissea.util.LogUtil.Init.*;

class FSFTest{
	static{
		LogUtil.Init.attach(USE_CALL_POS|USE_TABULATED_HEADER);
	}
	
	public static void main(String[] args){
		try{
			
			new File("testFileSystem.fsf").delete();
			FileSystemInFile fil=new FileSystemInFile(new File("testFileSystem.fsf"));
			
			
			int[]                       counter={0};
			UnsafeRunnable<IOException> snap   =()->ImageIO.write(fil.renderFile(24, 20), "png", new File("snap"+(counter[0]++)+".png"));
			
			snap.run();
			
			var testFile=fil.createFile("test.txt", 8);
			try(OutputStream os=testFile.write()){
				os.write("THIS WORKS!!!!".getBytes());
			}
			snap.run();
			
			try(OutputStream os=fil.getFile("test2.txt").write()){
				os.write("THIS REALLY WORKS!!!!".getBytes());
			}
			snap.run();
			
			testFile.writeAll("THIS W".getBytes());
			snap.run();
			testFile.writeAll("THIS WORKS!!!!".getBytes());
			testFile.writeAll("THIS W".getBytes());
			testFile.writeAll("THIS WORKS!!!!".getBytes());
			testFile.writeAll("THIS W".getBytes());
			testFile.writeAll("this works????".getBytes());
			snap.run();
			fil.createFile("test3.txt", 8).writeAll("1h234d56".getBytes());
			snap.run();
			fil.createFile("test4.txt", 8).writeAll("2341g634".getBytes());
			snap.run();
			fil.getFile("test3.txt").writeAll("1h234dsfa333333333333333333333333333333333sdfasdfd56".getBytes());
			snap.run();
			
			fil.defragment();
			snap.run();
			
			System.exit(0);
			
			LogUtil.println(new String(testFile.readAll()));
			
			fil.delete("");
			
			LogUtil.println(new String(fil.getFile("test2.txt").readAll()));
			
			fil.getFile("test.txt").writeAll("This is a very very very fucking long text that will force the file to grow.".getBytes());
			LogUtil.println(new String(fil.getFile("test.txt").readAll()));
			
			fil.getFile("test.txt").writeAll("This is a bit smaller text that will force the file to grow shrink.".getBytes());
			LogUtil.println(new String(fil.getFile("test.txt").readAll()));
			
			fil.getFile("test.txt").writeAll("This is small.".getBytes());
			LogUtil.println(new String(fil.getFile("test.txt").readAll()));
			
			fil.delete("");
			System.exit(0);
			
			fil.delete("test.txt");
			
			fil.getFile("test.txt");
		}catch(Throwable e){
			e.printStackTrace();
		}finally{
			System.exit(0);
		}
	}
	
}
