package com.lapissea.dfs.utils.iterableplus;

import java.util.Iterator;

public interface LongIterator extends Iterator<Long>{
	
	@Override
	boolean hasNext();
	
	@Override
	@Deprecated
	default Long next(){ return nextLong(); }
	
	long nextLong();
	
}
