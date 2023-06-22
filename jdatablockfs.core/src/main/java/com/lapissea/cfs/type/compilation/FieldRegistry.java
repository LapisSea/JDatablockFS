package com.lapissea.cfs.type.compilation;

import com.lapissea.cfs.internal.Runner;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.fields.reflection.IOFieldBooleanArray;
import com.lapissea.cfs.type.field.fields.reflection.IOFieldByteArray;
import com.lapissea.cfs.type.field.fields.reflection.IOFieldChunkPointer;
import com.lapissea.cfs.type.field.fields.reflection.IOFieldDynamicInlineObject;
import com.lapissea.cfs.type.field.fields.reflection.IOFieldDynamicReferenceObject;
import com.lapissea.cfs.type.field.fields.reflection.IOFieldEnum;
import com.lapissea.cfs.type.field.fields.reflection.IOFieldEnumArray;
import com.lapissea.cfs.type.field.fields.reflection.IOFieldEnumList;
import com.lapissea.cfs.type.field.fields.reflection.IOFieldFloatArray;
import com.lapissea.cfs.type.field.fields.reflection.IOFieldInlineObject;
import com.lapissea.cfs.type.field.fields.reflection.IOFieldPrimitive;
import com.lapissea.cfs.type.field.fields.reflection.InstanceCollection;
import com.lapissea.cfs.type.field.fields.reflection.wrappers.IOFieldDuration;
import com.lapissea.cfs.type.field.fields.reflection.wrappers.IOFieldInlineString;
import com.lapissea.cfs.type.field.fields.reflection.wrappers.IOFieldInstant;
import com.lapissea.cfs.type.field.fields.reflection.wrappers.IOFieldLocalDate;
import com.lapissea.cfs.type.field.fields.reflection.wrappers.IOFieldLocalDateTime;
import com.lapissea.cfs.type.field.fields.reflection.wrappers.IOFieldLocalTime;
import com.lapissea.cfs.type.field.fields.reflection.wrappers.IOFieldStringCollection;
import com.lapissea.util.LateInit;
import com.lapissea.util.UtilL;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

class FieldRegistry{
	static LateInit.Safe<IOField.FieldUsage.Registry> make(){
		return Runner.async(() -> {
			var                tasks  = new ArrayList<LateInit.Safe<IOField.FieldUsage>>();
			Consumer<Class<?>> submit = type -> tasks.add(Runner.async(() -> getFieldUsage(type)));
			submit.accept(IOFieldDynamicReferenceObject.class);
			submit.accept(IOFieldDynamicInlineObject.class);
			submit.accept(IOFieldPrimitive.class);
			submit.accept(IOFieldEnum.class);
			submit.accept(IOFieldChunkPointer.class);
			submit.accept(IOFieldByteArray.class);
			submit.accept(IOFieldBooleanArray.class);
			submit.accept(IOFieldFloatArray.class);
			submit.accept(InstanceCollection.class);
			submit.accept(IOFieldInlineString.class);
			submit.accept(IOFieldDuration.class);
			submit.accept(IOFieldInstant.class);
			submit.accept(IOFieldLocalDate.class);
			submit.accept(IOFieldLocalTime.class);
			submit.accept(IOFieldLocalDateTime.class);
			submit.accept(IOFieldStringCollection.class);
			submit.accept(IOFieldInlineObject.class);
			submit.accept(IOFieldEnumArray.class);
			submit.accept(IOFieldEnumList.class);
			
			var usages = tasks.stream()
			                  .map(LateInit::get)
			                  .toList();
//			NanoTimer          t      = new NanoTimer.Simple();
//			t.start();
//			t.end();
//			LogUtil.println(t);
//			System.exit(0);
			return new IOField.FieldUsage.Registry(usages);
		});
	}
	
	private static IOField.FieldUsage getFieldUsage(Class<?> type){
		var usageClasses = Optional.ofNullable(type.getDeclaredAnnotation(IOField.FieldUsageRef.class))
		                           .map(IOField.FieldUsageRef::value).filter(a -> a.length>0)
		                           .map(List::of).orElseGet(() -> {
				var l = Arrays.stream(type.getDeclaredClasses())
				              .filter(c -> UtilL.instanceOf(c, IOField.FieldUsage.class))
				              .map(c -> (Class<IOField.FieldUsage>)c)
				              .toList();
				if(l.isEmpty()){
					throw new IllegalStateException(
						type.getName() + " has no " + IOField.FieldUsageRef.class.getName() + " annotation nor has " +
						IOField.FieldUsage.class.getName() + " inner class(es). Please define one of them");
				}
				return l;
			});
		
		var usages = usageClasses.stream().map(FieldRegistry::make).toList();
		if(usages.size() == 1) return usages.get(0);
		return new IOField.FieldUsage.AnyOf(usages);
	}
	
	private static IOField.FieldUsage make(Class<IOField.FieldUsage> usageClass){
		Constructor<IOField.FieldUsage> constr;
		try{
			constr = usageClass.getDeclaredConstructor();
		}catch(NoSuchMethodException e){
			throw UtilL.exitWithErrorMsg(usageClass.getName() + " does not have an empty constructor");
		}
		try{
			constr.setAccessible(true);
			return constr.newInstance();
		}catch(ReflectiveOperationException e){
			throw new RuntimeException("There was an issue instantiating " + usageClass.getName(), e);
		}
	}
}
