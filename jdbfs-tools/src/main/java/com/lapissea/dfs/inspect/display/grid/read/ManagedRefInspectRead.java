package com.lapissea.dfs.inspect.display.grid.read;

import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.inspect.display.grid.DataPos;
import com.lapissea.dfs.inspect.display.grid.FieldReader;
import com.lapissea.dfs.io.RandomIO;
import com.lapissea.dfs.io.instancepipe.StructPipe;
import com.lapissea.dfs.objects.ChunkPointer;
import com.lapissea.dfs.tools.DrawUtils;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.VarPool;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.fields.RefField;

import java.io.IOException;
import java.util.List;

public class ManagedRefInspectRead implements FieldInspectRead{
	
	@Override
	public <T extends IOInstance<T>> ReadResult<T, ?> read(IOField<T, Object> field, VarPool<T> ioPool, DataProvider dataProvider, RandomIO src, T inst) throws IOException{
		
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
		
		return res.withRef(fieldPos, ref, field.getType(), () -> {
			@SuppressWarnings("unchecked")
			StructPipe<T> pipe = (StructPipe<T>)(Object)refField.getReferencedPipe(inst);
			if(pipe.getType() instanceof Struct.Unmanaged){
				return new FieldReader.ResSet<>(null, new DataPos.Sized(ChunkPointer.NULL, new DrawUtils.Range(0, 0)), List.of(), List.of());
			}
			return FieldReader.readFields(dataProvider, pipe, DataPos.from(ref));
		});
	}
}
