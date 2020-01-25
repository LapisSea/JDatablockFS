package test;

import com.lapissea.fsf.FileSystemInFile;
import com.lapissea.fsf.IOInterface;
import com.lapissea.util.LogUtil;
import com.lapissea.util.function.UnsafeConsumer;
import com.lapissea.util.function.UnsafeRunnable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;

class FSFTest_freeChunks{
	
	public static final long[] NO_IDS=new long[0];
	
	static{
//		LogUtil.Init.attach(0);
		LogUtil.Init.attach(LogUtil.Init.USE_CALL_POS|LogUtil.Init.USE_TABULATED_HEADER);
	}
	
	public static void main(String[] args) throws Exception{
		
		for(File file : new File(".").listFiles()){
			file.delete();
		}
		
		var snapshots=new ArrayList<Renderer.Snapshot>();
		
		
		var      pixelScale=13;
		Renderer renderer  =new Renderer.None();
//		Renderer renderer=new Renderer.GUI(pixelScale);
		
		
		UnsafeRunnable<IOException> snapshot=()->{};
		try{
			var source=new IOInterface.MemoryRA();
			var fil   =new FileSystemInFile(source);
			
			UnsafeConsumer<long[], IOException> snapshotIds=ids->{
				var copy=new FileSystemInFile(new IOInterface.MemoryRA(source));
//				Assert(fil.equals(copy));
				
				renderer.snapshot(new Renderer.Snapshot(copy, ids, new Throwable("Clicked snapshot")));
			};
			
			if(renderer.doAll()) source.onWrite=snapshotIds;
			snapshot=()->snapshotIds.accept(NO_IDS);
			
			
			snapshot.run();
			
			fil.getFile("buffer").writeAll(new byte[200]);
			fil.getFile("buffer2").writeAll(new byte[2]);
			fil.getFile("buffer").delete();

//			int count=4;
//			for(int i=0;i<count;i++){
//				fil.getFile("F"+i).writeAll(new byte[300]);
//			}
			
			fil.defragment();
			
			snapshot.run();
		}catch(Throwable e){
			e.printStackTrace();
		}finally{
			
			try{
				snapshot.run();
			}catch(Throwable ignored){}
			
			renderer.finish();
		}
	}
	
}
