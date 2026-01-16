package com.lapissea.dfs.inspect.display.grid.read;

import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.inspect.display.grid.DataPos;
import com.lapissea.dfs.inspect.display.grid.FieldReader;
import com.lapissea.dfs.io.RandomIO;
import com.lapissea.dfs.io.instancepipe.StructPipe;
import com.lapissea.dfs.type.GenericContext;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.IOType;
import com.lapissea.dfs.type.VarPool;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.fields.RefField;

import java.io.IOException;

public class UnmanagedRefInspectRead implements FieldInspectRead{
	
	@SuppressWarnings({"rawtypes", "unchecked"})
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
		
		return res.withRef(fieldPos, DataPos.from(ref), field.getType(), () -> {
			var pipe = (StructPipe)refField.getReferencedPipe(inst);
			var type = IOType.of(refField.getAccessor().getGenericType(genericContext));
			try{
				return FieldReader.readUnmanaged(dataProvider, pipe, ref.asPtr(), type, genericContext);
			}catch(IOException e){
				new IOException("Failed to read unmanaged value of " + type + " on " + ref.asPtr(), e).printStackTrace();
				return FieldReader.ResSet.empty();
			}
		});
	}
}
