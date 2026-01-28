package com.lapissea.dfs.inspect.display.grid.read;

import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.inspect.display.grid.FieldReader;
import com.lapissea.dfs.io.RandomIO;
import com.lapissea.dfs.io.instancepipe.StructPipe;
import com.lapissea.dfs.type.GenericContext;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.VarPool;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.fields.reflection.IOFieldInlineObject;

import java.io.IOException;

public class SimpleReadInspectRead implements FieldInspectRead{
	
	@Override
	public <T extends IOInstance<T>> FieldReader.Res<T, ?> read(IOField<T, Object> field, VarPool<T> ioPool, DataProvider dataProvider, RandomIO src, T inst, GenericContext genericContext) throws IOException{
		
		var pos = readOrSkip(field, ioPool, dataProvider, src, inst);
		
		var value = field.get(ioPool, inst);
		
		var res = FieldReader.res(field, value, pos);
		
		//noinspection rawtypes
		if(value != null && field instanceof IOFieldInlineObject f){
			var pipe      = (StructPipe<?>)f.getInstancePipe();
			var isBuilder = pipe.getType().needsBuilderObj();
			if(isBuilder){
				pipe = pipe.getBuilderPipe();
			}
			var inner = FieldReader.readManaged(dataProvider, pipe, pos.withoutSize(), genericContext);
			assert inner.pos().equals(pos) : inner.pos() + " != " + pos;
			res = res.withInner(inner);
		}
		
		return res;
	}
}
