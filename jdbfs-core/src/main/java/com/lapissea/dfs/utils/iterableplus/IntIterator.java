package com.lapissea.dfs.utils.iterableplus;

import java.util.Iterator;

public interface IntIterator extends Iterator<Integer>{
	
	@Override
	boolean hasNext();
	
	@Override
	@Deprecated
	default Integer next(){ return nextInt(); }
	
	int nextInt();
	
}
