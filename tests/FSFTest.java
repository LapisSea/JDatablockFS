import com.lapissea.fsf.FileSystemInFile;
import com.lapissea.fsf.IOInterface;
import com.lapissea.util.LogUtil;
import com.lapissea.util.function.UnsafeConsumer;
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
//			var l=new ArrayList<>(List.of("t", "a"));
//
//
//			int[] orderIndex=IntStream.range(0, l.size())
//			                          .boxed()
//			                          .sorted(Comparator.comparing(l::get))
//			                          .mapToInt(Integer::intValue)
//			                          .toArray();
//
//			LogUtil.println(orderIndex);
//			LogUtil.println(l);
//			for(int index : orderIndex){
//				LogUtil.println(l.get(index));
//			}
//			l.sort(Comparator.comparing(a->a));
//			LogUtil.println(l);
//			System.exit(0);
			
			
			for(File file : new File(".").listFiles()){
				file.delete();
			}
			var              source=new IOInterface.FileRA(new File("testFileSystem.fsf"));
			FileSystemInFile fil   =new FileSystemInFile(source);
			
			
			int[] counter={0};
			UnsafeConsumer<long[], IOException> snap0=ids->{
				var img=fil.renderFile(26, 26, ids);
				ImageIO.write(img, "png", new File("snap"+(counter[0]++)+".png"));
			};
			UnsafeRunnable<IOException> snap=()->snap0.accept(new long[0]);

//			source.onWrite=snap0;
			
			snap.run();
			
			var testFile=fil.createFile("test.txt", 8);
			try(OutputStream os=testFile.write()){
				os.write("THIS WORKS!!!!".getBytes());
			}
			LogUtil.println(fil.getFile("test.txt").readAllString());
			
			snap.run();
			
			try(OutputStream os=fil.getFile("aaaaaaa").write()){
				os.write("THIS REALLY WORKS!!!!".getBytes());
			}
			snap.run();
			LogUtil.println(fil.getFile("aaaaaaa").readAllString());
			System.exit(0);
			
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
			
			LogUtil.println(testFile.readAllString());
			
			fil.delete("");
			
			LogUtil.println(fil.getFile("test2.txt").readAllString());
			
			fil.getFile("test.txt").writeAll("This is a very very very fucking long text that will force the file to grow.".getBytes());
			LogUtil.println(fil.getFile("test.txt").readAllString());
			
			fil.getFile("test.txt").writeAll("This is a bit smaller text that will force the file to grow shrink.".getBytes());
			LogUtil.println(fil.getFile("test.txt").readAllString());
			
			fil.getFile("test.txt").writeAll("This is small.".getBytes());
			LogUtil.println(fil.getFile("test.txt").readAllString());
			
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
