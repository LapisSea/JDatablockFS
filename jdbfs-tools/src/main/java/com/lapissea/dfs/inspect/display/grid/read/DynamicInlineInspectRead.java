package com.lapissea.dfs.inspect.display.grid.read;

import com.lapissea.dfs.inspect.display.grid.FieldReader;
import com.lapissea.dfs.io.instancepipe.StructPipe;
import com.lapissea.dfs.type.IOInstance;

import java.io.IOException;

public class DynamicInlineInspectRead extends FieldInspectRead{
	
	@Override
	public <T extends IOInstance<T>> FieldReader.Res<T, ?> read(ReadCtx<T> ctx) throws IOException{
		var res = stdRes(ctx);
		
		if(res.value() == null || !(res.value() instanceof IOInstance<?> instVal)){
			return res;
		}
		
		StructPipe<?> pipe = getStructPipe(ctx, instVal);
		
		var isBuilder = pipe.getType().needsBuilderObj();
		if(isBuilder){
			pipe = pipe.getBuilderPipe();
		}
		var pos   = res.pos();
		var inner = FieldReader.readManaged(ctx.dataProvider, pipe, pos.withoutSize(), ctx.genericContext, ctx.path + " -> " + ctx.field);
		assert inner.pos().equals(pos) : inner.pos() + " != " + pos;
		res = res.withInner(inner);
		
		return res;
	}
}
