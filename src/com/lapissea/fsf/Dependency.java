package com.lapissea.fsf;

import com.lapissea.fsf.chunk.ChunkPointer;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public interface Dependency{
	
	default List<ChunkPointer> treeWalk(){
		List<ChunkPointer> buffer=new ArrayList<>();
		treeWalk(buffer::add);
		return buffer;
	}
	
	default void treeWalk(Consumer<ChunkPointer> dest){
		dest.accept(getValue());
		for(Dependency dependency : getDependencies()){
			dependency.treeWalk(dest);
		}
	}
	
	List<Dependency> getDependencies();
	
	ChunkPointer getValue();
}
