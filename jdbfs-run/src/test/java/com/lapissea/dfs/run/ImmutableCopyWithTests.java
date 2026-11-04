package com.lapissea.dfs.run;

import com.lapissea.dfs.core.AllocateTicket;
import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.core.DefragmentManager;
import com.lapissea.dfs.io.instancepipe.StructPipe;
import com.lapissea.dfs.objects.ChunkPointer;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.compilation.BuilderProxyCompiler;
import com.lapissea.dfs.type.field.annotations.IOValue;
import org.testng.annotations.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class ImmutableCopyWithTests{
	
	@IOValue
	@StructPipe.Special
	@IOInstance.Order({"tag", "ptr", "label"})
	public static class Pinned extends IOInstance.Managed<Pinned>{
		private final long         tag;
		private final ChunkPointer ptr;
		private final String       label;
		public Pinned(long tag, ChunkPointer ptr, String label){
			this.tag = tag;
			this.ptr = ptr;
			this.label = label;
		}
	}
	
	@IOValue
	public static class Root extends IOInstance.Managed<Root>{
		private Pinned pinned;
		public Root(){ }
	}
	
	@Test(timeOut = 60_000)
	void readOnlyPointerIsRewrittenOnMove() throws Exception{
		var cluster = Cluster.emptyMem();
		
		var target = AllocateTicket.bytes(64).submit(cluster);
		
		var root = new Root();
		root.pinned = new Pinned(1234, target.getPtr(), "kept");
		cluster.roots().provide("root", root);
		
		var move = DefragmentManager.moveReference(cluster, target.getPtr(), t -> t);
		assertThat(move.hasAny()).as("the target chunk must actually move").isTrue();
		var newPtr = move.toDest(target.getPtr()).orElseThrow();
		assertThat(newPtr).isNotEqualTo(target.getPtr());
		
		var reopened = TestUtils.reopenMem(cluster).roots().request("root", Root.class);
		assertThat(reopened.pinned.ptr).as("pointer rewritten through copyWith").isEqualTo(newPtr);
		assertThat(reopened.pinned.tag).as("other fields kept").isEqualTo(1234);
		assertThat(reopened.pinned.label).isEqualTo("kept");
		
		var concrete = Struct.of(Pinned.class).getConcreteType();
		assertThat(builderExists(concrete.getName() + BuilderProxyCompiler.BUILDER_PROXY_POSTFIX, concrete.getClassLoader()))
			.as("copyWith must not compile a builder").isFalse();
	}
	
	private static boolean builderExists(String name, ClassLoader loader){
		try{
			Class.forName(name, false, loader);
			return true;
		}catch(ClassNotFoundException e){
			return false;
		}
	}
}
