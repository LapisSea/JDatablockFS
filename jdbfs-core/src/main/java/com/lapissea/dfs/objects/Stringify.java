package com.lapissea.dfs.objects;

import com.lapissea.util.TextUtil;

public interface Stringify{
	
	default String toShortString(){
		return TextUtil.toString(this);
	}
}
