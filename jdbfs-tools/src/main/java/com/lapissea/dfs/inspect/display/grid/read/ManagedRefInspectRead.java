package com.lapissea.dfs.inspect.display.grid.read;

import com.lapissea.dfs.inspect.display.grid.DataPos;
import com.lapissea.dfs.inspect.display.grid.FieldReader;
import com.lapissea.dfs.io.instancepipe.StructPipe;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.field.fields.RefField;

import java.io.IOException;

public class ManagedRefInspectRead extends FieldInspectRead{
	
	@Override
	public <T extends IOInstance<T>> FieldReader.Res<T, ?> read(ReadCtx<T> ctx) throws IOException{
		var res = stdRes(ctx);
		
		var refField = (RefField<T, Object>)ctx.field;
		var ref      = refField.getReference(ctx.inst);
		if(ref == null || ref.isNull()){
			return res;
		}
		
		var refPos = DataPos.from(ref);
		
		@SuppressWarnings("unchecked")
		StructPipe<T> pipe = (StructPipe<T>)(Object)refField.getReferencedPipe(ctx.inst);
		var refVal = FieldReader.readFields(ctx.dataProvider, pipe, refPos, ctx.genericContext, ctx.path + " -> " + ctx.field);
		
		return res.withRef(res.pos(), refPos, ctx.field.getType(), refVal);
	}
}
