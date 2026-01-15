package com.lapissea.dfs.inspect.display.grid.read;

import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.io.RandomIO;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.VarPool;
import com.lapissea.dfs.type.field.IOField;

import java.io.IOException;

public class SimpleReadInspectRead implements FieldInspectRead{
	
	@Override
	public <T extends IOInstance<T>> ReadResult<T, ?> read(IOField<T, Object> field, VarPool<T> ioPool, DataProvider dataProvider, RandomIO src, T inst) throws IOException{
		
		var start = src.getPos();
		field.read(ioPool, dataProvider, src, inst, null);
		var end = src.getPos();
		
		var value = field.get(ioPool, inst);
		
		return ReadResult.res(field, value, defaultPos(src, start, end));
	}
}
