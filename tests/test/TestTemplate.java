package test;

import com.lapissea.fsf.FileSystemInFile;
import com.lapissea.fsf.Renderer;
import com.lapissea.fsf.endpoint.IFileSystem;
import com.lapissea.fsf.endpoint.IdentifierIO;
import com.lapissea.fsf.endpoint.data.MemoryData;
import com.lapissea.util.LogUtil;
import com.lapissea.util.function.UnsafeConsumer;
import com.lapissea.util.function.UnsafeRunnable;

import java.io.IOException;
import java.util.function.Supplier;

public class TestTemplate{
	
	public interface Runner{
		void run(IFileSystem<String> fil, UnsafeRunnable<IOException> snapshot) throws IOException;
	}
	
	private static final long[] NO_IDS=new long[0];
	
	public static void run(Supplier<Renderer> rendererSupplier, Runner run){
		
		Renderer renderer=rendererSupplier.get();
		
		UnsafeRunnable<IOException> snapshot;
		try{
			var source=new MemoryData(false);
			var fil   =new FileSystemInFile<>(source, IdentifierIO.STRING);
			
			UnsafeConsumer<long[], IOException> snapshotIds=ids->{
				var copy=new FileSystemInFile<>(new MemoryData(source, true), IdentifierIO.STRING);
				
				renderer.snapshot(new Renderer.Snapshot(copy, ids, new Throwable("Clicked snapshot")));
				
				copy.header.validateFile();

//				var diff=fil.findContentDifference(copy);
//				if(diff!=null){
//					var i1=fil.header.allChunkWalkerFlat(true);
//					var i2=copy.header.allChunkWalkerFlat(true);
//
//					List<Map<?, ?>> table=new ArrayList<>();
//
//					while(i1.hasNext()||i2.hasNext()){
//						Chunk o1=i1.hasNext()?i1.next():null;
//						Chunk o2=i2.hasNext()?i2.next():null;
//
//						Map<String, Object> row=new LinkedHashMap<>();
//						row.put("file", o1);
//						row.put("copy", o2);
//						if((o1!=null&&diff.stream().anyMatch(o->o==o1))||
//						   (o2!=null&&diff.stream().anyMatch(o->o==o2)))
//							row.put("diff", "<--");
//
//						table.add(row);
//					}
//
//					throw new AssertionError("copy not equal\n"+TextUtil.toTable(table));
//				}
			};
			
			if(renderer.doAll()) source.onWrite=snapshotIds;
			snapshot=()->snapshotIds.accept(NO_IDS);
			
			snapshot.run();
			
			run.run(fil, snapshot);
		}catch(Throwable e){
			e.printStackTrace();
		}finally{
			renderer.finish();
		}
	}
}
