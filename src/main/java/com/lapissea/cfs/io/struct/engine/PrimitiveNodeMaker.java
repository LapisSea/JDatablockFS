package com.lapissea.cfs.io.struct.engine;

import com.lapissea.cfs.exceptions.MalformedStructLayout;
import com.lapissea.cfs.io.struct.IOStruct;
import com.lapissea.cfs.io.struct.StructImpl;
import com.lapissea.cfs.io.struct.ValueRelations;
import com.lapissea.cfs.io.struct.VariableNode;
import com.lapissea.cfs.io.struct.engine.impl.*;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.util.NotImplementedException;

import java.lang.reflect.Field;

public class PrimitiveNodeMaker extends StructReflectionImpl.NodeMaker{
	@SuppressWarnings("unchecked")
	@Override
	protected VariableNode<?> makeNode(IOStruct clazz, String name, ValueRelations.ValueInfo info){
		IOStruct.PrimitiveValue valueAnn;
		Field                   valueField;
		{
			StructImpl.Annotated<Field, ?> val=info.value();
			
			valueAnn=(IOStruct.PrimitiveValue)val.annotation();
			
			valueField=val.val();
			valueField.setAccessible(true);
		}
		
		var valueType=valueField.getType();
		
		VariableNode.VarInfo vInfo=new VariableNode.VarInfo(name, valueAnn.index());
		
		if(valueType==boolean.class) return new BoolIOImpl(vInfo, valueField, IOStruct.Get.GetterB.get(info), IOStruct.Set.SetterB.get(info));
		
		if(valueType==long.class){
			NumberSize fixedSize=valueAnn.defaultSize();
			if(fixedSize==NumberSize.VOID) fixedSize=NumberSize.LARGEST;
			
			if(!valueAnn.sizeRef().isEmpty()){
				try{
					Field varSize=clazz.instanceClass.getDeclaredField(valueAnn.sizeRef());
					if(varSize.getType()!=NumberSize.class) throw new ClassCastException(varSize+" must be a NumberSize enum!");
					varSize.setAccessible(true);
					
					return new LongVarSizeIOImpl(vInfo, valueField, IOStruct.Get.GetterL.get(info), IOStruct.Set.SetterL.get(info), varSize, fixedSize);
				}catch(ReflectiveOperationException e){
					throw new MalformedStructLayout(e);
				}
			}
			
			return new LongIOImpl(vInfo, valueField, IOStruct.Get.GetterL.get(info), IOStruct.Set.SetterL.get(info), fixedSize);
		}
		
		if(valueType==int.class){
			NumberSize fixedSize=valueAnn.defaultSize();
			if(fixedSize==NumberSize.VOID) fixedSize=NumberSize.INT;
			
			if(!valueAnn.sizeRef().isEmpty()){
				try{
					Field varSize=clazz.instanceClass.getDeclaredField(valueAnn.sizeRef());
					if(varSize.getType()!=NumberSize.class) throw new ClassCastException(varSize+" must be a NumberSize enum!");
					varSize.setAccessible(true);
					
					return new IntVarSizeIOImpl(vInfo, valueField, IOStruct.Get.GetterI.get(info), IOStruct.Set.SetterI.get(info), varSize, fixedSize);
				}catch(ReflectiveOperationException e){
					throw new MalformedStructLayout(e);
				}
			}
			
			return new IntIOImpl(vInfo, valueField, IOStruct.Get.GetterI.get(info), IOStruct.Set.SetterI.get(info), fixedSize);
		}
		if(valueType==float.class){
			NumberSize fixedSize=valueAnn.defaultSize();
			if(fixedSize==NumberSize.VOID) fixedSize=NumberSize.INT;
			
			if(!valueAnn.sizeRef().isEmpty()){
				try{
					Field varSize=clazz.instanceClass.getDeclaredField(valueAnn.sizeRef());
					if(varSize.getType()!=NumberSize.class) throw new ClassCastException(varSize+" must be a NumberSize enum!");
					varSize.setAccessible(true);
					
					return new FloatVarSizeIOImpl(vInfo, valueField, IOStruct.Get.GetterF.get(info), IOStruct.Set.SetterF.get(info), varSize, fixedSize);
				}catch(ReflectiveOperationException e){
					throw new RuntimeException(e);
				}
			}
			
			return new FloatIOImpl(vInfo, valueField, IOStruct.Get.GetterF.get(info), IOStruct.Set.SetterF.get(info), fixedSize);
		}
		
		throw new NotImplementedException(valueType.toString());
	}
}
