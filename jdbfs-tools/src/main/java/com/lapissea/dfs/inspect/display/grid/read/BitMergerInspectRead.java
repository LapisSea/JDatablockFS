package com.lapissea.dfs.inspect.display.grid.read;

import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.inspect.display.grid.FieldReader;
import com.lapissea.dfs.io.RandomIO;
import com.lapissea.dfs.type.GenericContext;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.VarPool;
import com.lapissea.dfs.type.field.IOField;

import java.io.IOException;

public class BitMergerInspectRead implements FieldInspectRead{
	
	@Override
	public <T extends IOInstance<T>> FieldReader.Res<T, ?> read(IOField<T, Object> field, VarPool<T> ioPool, DataProvider dataProvider, RandomIO src, T inst, GenericContext genericContext) throws IOException{
		
		field.read(ioPool, dataProvider, src, inst, null);
		
		return FieldReader.res(field, null, defaultPos(src, 0, 0));
	}
}
