package com.lapissea.dfs.inspect.display.grid.read;

import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.inspect.display.grid.DataPos;
import com.lapissea.dfs.inspect.display.grid.FieldReader;
import com.lapissea.dfs.io.RandomIO;
import com.lapissea.dfs.io.instancepipe.StructPipe;
import com.lapissea.dfs.type.GenericContext;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.VarPool;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.fields.RefField;

import java.io.IOException;

public class ManagedRefInspectRead implements FieldInspectRead{
	
	@Override
	public <T extends IOInstance<T>> ReadResult<T, ?> read(IOField<T, Object> field, VarPool<T> ioPool, DataProvider dataProvider, RandomIO src, T inst, GenericContext genericContext) throws IOException{
		
		var start = src.getPos();
		field.read(ioPool, dataProvider, src, inst, null);
		var end = src.getPos();
		
		
		var value = field.get(ioPool, inst);
		
		var fieldPos = defaultPos(src, start, end);
		var res      = ReadResult.res(field, value, fieldPos);
		
		var refField = (RefField<T, Object>)field;
		var ref      = refField.getReference(inst);
		if(ref == null || ref.isNull()){
			return res;
		}
		
		var pos = DataPos.from(ref);
		
		@SuppressWarnings("unchecked")
		StructPipe<T> pipe = (StructPipe<T>)(Object)refField.getReferencedPipe(inst);
		var refVal = FieldReader.readFields(dataProvider, pipe, pos, genericContext);
		
		return res.withRef(fieldPos, pos, field.getType(), refVal);
	}
}
