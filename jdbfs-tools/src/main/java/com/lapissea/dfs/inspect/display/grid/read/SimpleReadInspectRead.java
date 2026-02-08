package com.lapissea.dfs.inspect.display.grid.read;

import com.lapissea.dfs.inspect.display.grid.DataPos;
import com.lapissea.dfs.inspect.display.grid.FieldReader;
import com.lapissea.dfs.io.instancepipe.StructPipe;
import com.lapissea.dfs.type.IOInstance;

import java.io.IOException;

public class SimpleReadInspectRead extends FieldInspectRead{
	
	@Override
	public <T extends IOInstance<T>> FieldReader.Res<T, ?> read(ReadCtx<T> ctx) throws IOException{
		var res = stdRes(ctx);
		
		if(res.value() == null || !(res.value() instanceof IOInstance<?> instVal)){
			return res;
		}
		
		if(instVal instanceof IOInstance.Unmanaged<?> uInst){
			var unmanagedRes = FieldReader.readUnmanaged(ctx.dataProvider, uInst.getPipe(), uInst.getPointer(), uInst.getTypeDef(), ctx.path + " -> " + ctx.field);
			return res.withRef(res.pos(), DataPos.from(uInst.getPointer()), uInst.getClass(), unmanagedRes);
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
