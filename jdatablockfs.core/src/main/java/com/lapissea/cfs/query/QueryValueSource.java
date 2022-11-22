package com.lapissea.cfs.query;

import com.lapissea.cfs.type.field.IOField;
import com.lapissea.util.Nullable;
import com.lapissea.util.TextUtil;

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.stream.Stream;

public sealed interface QueryValueSource{
	default Stream<QueryValueSource> deep(){
		return Stream.concat(Stream.of(this), innerValues());
	}
	default Stream<QueryValueSource> innerValues(){
		return Stream.empty();
	}
	
	@Nullable
	Class<?> type();
	
	record Root() implements QueryValueSource{
		@Override
		public String toString(){
			return "ARG!";
		}
		@Override
		public Class<?> type(){
			throw new UnsupportedOperationException();
		}
	}
	
	record GetArray(int index, QueryValueSource source) implements QueryValueSource{
		@Override
		public String toString(){
			return source+"["+index+"]";
		}
		
		@Override
		public Stream<QueryValueSource> innerValues(){
			return source.deep();
		}
		@Override
		public Class<?> type(){
			return null;
		}
	}
	
	record Literal(Object value) implements QueryValueSource{
		@Override
		public String toString(){
			return switch(value){
				case String s -> "'"+s+"'";
				case Float s -> s+"F";
				case Double s -> s+"D";
				case Long s -> s+"L";
				default -> value+"";
			};
		}
		@Override
		public Class<?> type(){
			return value==null?Object.class:value.getClass();
		}
	}
	
	sealed interface Field extends QueryValueSource{
		Class<?> owner();
		String name();
		
		record IO(IOField<?, ?> field) implements Field{
			public IO{
				Objects.requireNonNull(field.declaringStruct());
			}
			
			@Override
			public Class<?> owner(){
				return field.declaringStruct().getType();
			}
			@Override
			public Class<?> type(){
				return field.getAccessor().getType();
			}
			@Override
			public String name(){
				return field.getName();
			}
			@Override
			public String toString(){
				return "#"+name();
			}
		}
		
		record Raw(java.lang.reflect.Field field) implements Field{
			
			@Override
			public Class<?> owner(){
				return field.getDeclaringClass();
			}
			@Override
			public Class<?> type(){
				return field.getType();
			}
			@Override
			public String name(){
				return field.getName();
			}
			@Override
			public String toString(){
				return "#"+name();
			}
		}
		
		record Getter(Method method, String name) implements Field{
			private static String strip(String name){
				if(name.length()>=4&&Character.isUpperCase(name.charAt(3))&&name.startsWith("get")){
					return TextUtil.firstToLowerCase(name.substring(3));
				}
				return name;
			}
			public Getter(Method method){
				this(method, strip(method.getName()));
			}
			
			@Override
			public Class<?> owner(){
				return method.getDeclaringClass();
			}
			@Override
			public Class<?> type(){
				return method.getReturnType();
			}
			@Override
			public String toString(){
				return "#"+name();
			}
		}
	}
	
	record Modulus(QueryValueSource src, QueryValueSource mod) implements QueryValueSource{
		@Override
		public String toString(){
			return src+"%"+mod;
		}
		
		@Override
		public Stream<QueryValueSource> innerValues(){
			return Stream.concat(src.deep(), mod.deep());
		}
		@Override
		public Class<?> type(){
			return src.type();
		}
	}
	
	record Add(QueryValueSource l, QueryValueSource r) implements QueryValueSource{
		@Override
		public String toString(){
			return l+" + "+r;
		}
		
		@Override
		public Stream<QueryValueSource> innerValues(){
			return Stream.concat(l.deep(), r.deep());
		}
		@Override
		public Class<?> type(){
			var l=this.l.type();
			var r=this.r.type();
			if(l!=null&&r!=null){
				return QueryUtils.addTyp(l, r);
			}
			return null;
		}
	}
}
