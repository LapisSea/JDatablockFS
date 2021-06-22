package test;

import com.lapissea.cfs.chunk.Chunk;
import com.lapissea.cfs.chunk.Cluster;
import com.lapissea.cfs.io.IOInterface;
import com.lapissea.cfs.io.impl.MemoryData;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.objects.collections.ContiguousIOList;
import com.lapissea.cfs.objects.text.AutoText;
import com.lapissea.cfs.type.Struct;
import com.lapissea.util.LogUtil;

import java.io.IOException;
import java.util.stream.Stream;

import static com.lapissea.util.LogUtil.Init.*;

public class TestRun{
	public static void main(String[] args) throws IOException{
		System.setProperty("com.lapissea.cfs.GlobalConfig.printCompilation", "true");
		
		LogUtil.Init.attach(USE_CALL_POS|USE_TABULATED_HEADER);
		
		Stream.of(Chunk.class, Reference.class, AutoText.class, ContiguousIOList.class).forEach(Struct::ofUnknown);
		
		IOInterface data=MemoryData.build().build();
		Cluster.init(data);
		
		var cluster=new Cluster(data);
//		LogUtil.println(cluster);
//		LogUtil.println(data);
		
		System.exit(0);
	}
}
