package com.lapissea.cfs.io.struct.engine;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.io.struct.*;
import com.lapissea.cfs.io.struct.engine.impl.GlobalSetIOImpl;

import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Type;

public class GlobalSetNodeMaker<T extends IOInstance> extends StructReflectionImpl.NodeMaker{
	
	@Override
	protected VariableNode<T> makeNode(IOStruct clazz, String name, ValueRelations.ValueInfo info){
		
		IOStruct.GlobalSetValue valueAnn;
		Field                   valueField;
		{
			StructImpl.Annotated<Field, ?> val=info.value();
			
			valueAnn=(IOStruct.GlobalSetValue)val.annotation();
			
			valueField=val.val();
			valueField.setAccessible(true);
		}
		
		VarHandle varh =Utils.makeVarHandle(valueField);
		var       vInfo=new VariableNode.VarInfo(name, valueAnn.index());
		
		Type                   type  =valueField.getGenericType();
		IOStruct.Get.Getter<T> getFun=IOStruct.Get.Getter.get(info, type);
		IOStruct.Set.Setter<T> setFun=IOStruct.Set.Setter.get(info, type);
		
		return new GlobalSetIOImpl<>(vInfo, varh, getFun, setFun, IOStruct.ofUnknown(valueField.getType()));
	}
}
