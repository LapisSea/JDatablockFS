package com.lapissea.cfs.io.struct.engine;

import com.lapissea.cfs.exceptions.MalformedStructLayout;
import com.lapissea.cfs.io.ReaderWriter;
import com.lapissea.cfs.io.SelfPoint;
import com.lapissea.cfs.io.struct.*;
import com.lapissea.cfs.io.struct.IOStruct.Construct.Constructor;
import com.lapissea.cfs.io.struct.IOStruct.Get.Getter;
import com.lapissea.cfs.io.struct.IOStruct.Set.Setter;
import com.lapissea.cfs.io.struct.engine.impl.StructPtrIOImpl;
import com.lapissea.cfs.objects.chunk.ObjectPointer;
import com.lapissea.util.UtilL;

import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.util.Objects;

public class PointerNodeMaker<T extends IOInstance&SelfPoint<T>> extends StructReflectionImpl.NodeMaker<T>{
	
	@Override
	@SuppressWarnings("unchecked")
	protected VariableNode<T> makeNode(Class<?> clazz, String name, ValueRelations.ValueInfo info){
		
		IOStruct              overrideType;
		IOStruct.PointerValue valueAnn;
		Field                 valueField;
		{
			StructImpl.Annotated<Field, ?> val=info.value();
			
			valueAnn=(IOStruct.PointerValue)val.annotation();
			
			valueField=val.val();
			valueField.setAccessible(true);
			
			overrideType=valueAnn.type()==IOInstance.class?null:IOStruct.get(valueAnn.type());
		}
		
		IOStruct structType;
		{
			var typDirty=valueField.getType();
			var typ     =UtilL.instanceOf(typDirty, IOInstance.class)?IOStruct.get((Class<IOInstance>)typDirty):null;
			
			if(overrideType==null){
				if(typ==null) throw new MalformedStructLayout(valueField.getName()+" is not a struct and has no explicit type defined");
				structType=typ;
			}else{
				if(typ!=null){
					if(!UtilL.instanceOf(overrideType.instanceClass, typ.instanceClass)){
						throw new ClassCastException(overrideType.instanceClass.getName()+" can not be cast to "+typ.instanceClass.getName());
					}
					structType=typ;
				}else{
					structType=overrideType;
				}
			}
		}
		
		Type valueType=valueField.getGenericType();
		
		Constructor<T> constructorFun=Constructor.get(info, valueType);
		
		if(constructorFun==null&&!structType.canInstate()){
			throw new MalformedStructLayout("value "+valueField.getName()+" in "+clazz.getName()+" is not auto constructable and does not have a constructor function!");
		}
		
		Getter<T> getFun=Getter.get(info, valueType);
		Setter<T> setFun=Setter.get(info, valueType);
		
		ReaderWriter<ObjectPointer<T>> rw=Objects.requireNonNull(ReaderWriter.getInstance(clazz, (Class<ReaderWriter<ObjectPointer<T>>>)((Object)valueAnn.rw()), valueAnn.rwArgs()));
		
		if(rw.getFixedSize().isPresent()){
			return new StructPtrIOImpl.Fixed<>(name, valueAnn.index(), valueField, getFun, setFun, constructorFun, rw, structType);
		}
		return new StructPtrIOImpl<>(name, valueAnn.index(), valueField, getFun, setFun, constructorFun, rw, structType);
	}
}
