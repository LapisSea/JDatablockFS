package com.lapissea.dfs.type;

import com.lapissea.dfs.objects.Stringify;
import com.lapissea.util.UtilL;

import java.util.List;

final class CustomStringForms{
	
	private static final int TO_STRING       = 1;
	private static final int TO_SHORT_STRING = 2;
	
	private static final ClassValue<Integer> FLAGS = new ClassValue<>(){
		
		private static final List<Class<?>> ROOT_TYPES = List.of(
			Object.class, Stringify.class, IOInstance.class, IOInstance.Managed.class, IOInstance.Unmanaged.class
		);
		private static boolean isCustom(Class<?> type, String name){
			try{
				var declaring = type.getMethod(name).getDeclaringClass();
				return !ROOT_TYPES.contains(declaring);
			}catch(NoSuchMethodException e){
				return false;
			}
		}
		
		@Override
		protected Integer computeValue(Class<?> type){
			int flags = 0;
			if(isCustom(type, "toString")) flags |= TO_STRING;
			if(isCustom(type, "toShortString")) flags |= TO_SHORT_STRING;
			return flags;
		}
	};
	
	private CustomStringForms(){ }
	
	static boolean hasToString(Class<?> type){
		return UtilL.checkFlag(FLAGS.get(type), TO_STRING);
	}
	
	static boolean hasToShortString(Class<?> type){
		return UtilL.checkFlag(FLAGS.get(type), TO_SHORT_STRING);
	}
}
