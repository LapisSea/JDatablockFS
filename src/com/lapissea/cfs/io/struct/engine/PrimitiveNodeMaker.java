package com.lapissea.cfs.io.struct.engine;

import com.lapissea.cfs.io.struct.IOStruct;
import com.lapissea.cfs.io.struct.StructImpl;
import com.lapissea.cfs.io.struct.ValueRelations;
import com.lapissea.cfs.io.struct.VariableNode;
import com.lapissea.cfs.io.struct.engine.impl.*;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.util.NotImplementedException;

import java.lang.reflect.Field;

public class PrimitiveNodeMaker<T> extends StructReflectionImpl.NodeMaker<T>{
	@SuppressWarnings("unchecked")
	@Override
	protected VariableNode<T> makeNode(Class<?> clazz, String name, ValueRelations.ValueInfo info){
		IOStruct.PrimitiveValue valueAnn;
		Field                   valueField;
		{
			StructImpl.Annotated<Field, ?> val=info.value();
			
			valueAnn=(IOStruct.PrimitiveValue)val.annotation();
			
			valueField=val.val();
			valueField.setAccessible(true);
		}
		
		var valueType=valueField.getType();
		
		if(valueType==boolean.class) return (VariableNode<T>)new BoolIOImpl(name, valueAnn.index(), valueField, IOStruct.Get.GetterB.get(info), IOStruct.Set.SetterB.get(info));
		
		if(valueType==long.class){
			NumberSize fixedSize=valueAnn.defaultSize();
			if(fixedSize==NumberSize.VOID) fixedSize=NumberSize.LONG;
			
			if(!valueAnn.sizeRef().isEmpty()){
				try{
					Field varSize=clazz.getDeclaredField(valueAnn.sizeRef());
					if(varSize.getType()!=NumberSize.class) throw new ClassCastException(varSize+" must be a NumberSize enum!");
					varSize.setAccessible(true);
					
					return (VariableNode<T>)new LongVarSizeIOImpl(name, valueAnn.index(), valueField, IOStruct.Get.GetterL.get(info), IOStruct.Set.SetterL.get(info), varSize, fixedSize);
				}catch(ReflectiveOperationException e){
					throw new RuntimeException(e);
				}
			}
			
			return (VariableNode<T>)new LongIOImpl(name, valueAnn.index(), valueField, IOStruct.Get.GetterL.get(info), IOStruct.Set.SetterL.get(info), fixedSize);
		}
		
		if(valueType==int.class){
			NumberSize fixedSize=valueAnn.defaultSize();
			if(fixedSize==NumberSize.VOID) fixedSize=NumberSize.INT;
			
			if(!valueAnn.sizeRef().isEmpty()){
				try{
					Field varSize=clazz.getDeclaredField(valueAnn.sizeRef());
					if(varSize.getType()!=NumberSize.class) throw new ClassCastException(varSize+" must be a NumberSize enum!");
					varSize.setAccessible(true);
					
					return (VariableNode<T>)new IntVarSizeIOImpl(name, valueAnn.index(), valueField, IOStruct.Get.GetterI.get(info), IOStruct.Set.SetterI.get(info), varSize, fixedSize);
				}catch(ReflectiveOperationException e){
					throw new RuntimeException(e);
				}
			}
			
			return (VariableNode<T>)new IntIOImpl(name, valueAnn.index(), valueField, IOStruct.Get.GetterI.get(info), IOStruct.Set.SetterI.get(info), fixedSize);
		}
		
		throw new NotImplementedException(valueType.toString());
	}
}
