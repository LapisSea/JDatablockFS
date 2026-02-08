package com.lapissea.dfs.inspect.display.grid.read;

import com.lapissea.dfs.inspect.display.grid.DataPos;
import com.lapissea.dfs.inspect.display.grid.FieldReader;
import com.lapissea.dfs.io.instancepipe.StructPipe;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.IOType;
import com.lapissea.dfs.type.field.fields.RefField;

import java.io.IOException;

public class UnmanagedRefInspectRead extends FieldInspectRead{
	
	@SuppressWarnings({"rawtypes", "unchecked"})
	@Override
	public <T extends IOInstance<T>> FieldReader.Res<T, ?> read(ReadCtx<T> ctx) throws IOException{
		var res = stdRes(ctx);
		
		var refField = (RefField<T, Object>)ctx.field;
		var ref      = refField.getReference(ctx.inst);
		if(ref == null || ref.isNull()){
			return res;
		}
		
		FieldReader.ResSet<?> refVal;
		
		var pipe = (StructPipe)refField.getReferencedPipe(ctx.inst);
		var type = IOType.of(refField.getAccessor().getGenericType(ctx.genericContext));
		try{
			refVal = FieldReader.readUnmanaged(ctx.dataProvider, pipe, ref.asPtr(), type, ctx.path + " -> " + ctx.field);
		}catch(IOException e){
			new IOException("Failed to read unmanaged value of " + type + " on " + ref.asPtr(), e).printStackTrace();
			refVal = FieldReader.ResSet.empty();
		}
		
		return res.withRef(res.pos(), DataPos.from(ref), ctx.field.getType(), refVal);
	}
}
