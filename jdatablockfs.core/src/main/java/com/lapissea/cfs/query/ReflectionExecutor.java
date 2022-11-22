package com.lapissea.cfs.query;

import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.util.UtilL;

import java.lang.reflect.Array;
import java.util.List;
import java.util.Objects;

public enum ReflectionExecutor{
	;
	
	private static Object addAB(Number l, Number r){
		return switch(l){
			case Integer a -> switch(r){
				case Integer b -> a+b;
				case Long b -> a+b;
				case Double b -> a+b;
				case Float b -> a+b;
				default -> throw new IllegalStateException("Unexpected value: "+l);
			};
			case Long a -> switch(r){
				case Integer b -> a+b;
				case Long b -> a+b;
				case Double b -> a+b;
				case Float b -> a+b;
				default -> throw new IllegalStateException("Unexpected value: "+l);
			};
			case Double a -> switch(r){
				case Integer b -> a+b;
				case Long b -> a+b;
				case Double b -> a+b;
				case Float b -> a+b;
				default -> throw new IllegalStateException("Unexpected value: "+l);
			};
			case Float a -> switch(r){
				case Integer b -> a+b;
				case Long b -> a+b;
				case Double b -> a+b;
				case Float b -> a+b;
				default -> throw new IllegalStateException("Unexpected value: "+l);
			};
			default -> throw new IllegalStateException("Unexpected value: "+l);
		};
	}
	
	private static Object getArg(QueryContext ctx, QueryValueSource arg){
		return switch(arg){
			case QueryValueSource.GetArray getArray -> Array.get(getArg(ctx, getArray.source()), getArray.index());
			case QueryValueSource.Root ignored -> ctx.args();
			case QueryValueSource.Literal literal -> literal.value();
			case QueryValueSource.Field.IO io -> ((IOField)io.field()).get(null, (IOInstance)ctx.obj());
			case QueryValueSource.Field.Getter getter -> {
				var m=getter.method();
				try{
					yield m.invoke(ctx.obj());
				}catch(ReflectiveOperationException e){
					throw new RuntimeException(e);
				}
			}
			case QueryValueSource.Field.Raw raw -> {
				var m=raw.field();
				try{
					yield m.get(ctx.obj());
				}catch(ReflectiveOperationException e){
					throw new RuntimeException(e);
				}
			}
			case QueryValueSource.Modulus modulus -> {
				var src=getArg(ctx, modulus.src());
				var mod=((Number)getArg(ctx, modulus.mod())).intValue();
				yield switch(src){
					case Integer n -> n%mod;
					case Long n -> n%mod;
					case Double n -> n%mod;
					case Float n -> n%mod;
					default -> throw new IllegalStateException("Unexpected value: "+src);
				};
			}
			case QueryValueSource.Add add -> {
				var l=(Number)getArg(ctx, add.l());
				var r=(Number)getArg(ctx, add.r());
				yield addAB(l, r);
			}
		};
	}
	
	public static boolean executeCheck(QueryContext ctx, QueryCheck check){
		return switch(check){
			case QueryCheck.And and -> {
				var l=executeCheck(ctx, and.l());
				var r=executeCheck(ctx, and.r());
				yield l&&r;
			}
			case QueryCheck.Or or -> {
				var l=executeCheck(ctx, or.l());
				if(l) yield true;
				var r=executeCheck(ctx, or.r());
				yield r;
			}
			case QueryCheck.Not not -> !executeCheck(ctx, not.check());
			case QueryCheck.Equals equals -> {
				Object val=getArg(ctx, equals.l());
				Object arg=getArg(ctx, equals.r());
				if(arg==null) yield val==null;
				
				if(arg instanceof Number argN&&val instanceof Number valN){
					if(valN instanceof Double d){
						yield d==argN.doubleValue();
					}
					if(valN instanceof Float f){
						yield f==argN.floatValue();
					}
					yield valN.longValue()==argN.longValue();
				}
				
				if(!UtilL.instanceOf(arg.getClass(), val.getClass())){
					throw new ClassCastException(arg+" not compatible with "+equals.l());
				}
				yield arg.equals(val);
			}
			case QueryCheck.GreaterThan equals -> {
				var val=(Number)getArg(ctx, equals.field());
				var arg=(Number)getArg(ctx, equals.arg());
				yield val.doubleValue()>arg.doubleValue();
			}
			case QueryCheck.LessThan equals -> {
				var val=(Number)getArg(ctx, equals.field());
				var arg=(Number)getArg(ctx, equals.arg());
				yield val.doubleValue()<arg.doubleValue();
			}
			case QueryCheck.In in -> {
				var needle=getArg(ctx, in.needle());
				var hay   =getArg(ctx, in.hay());
				if(hay==null) yield false;
				if(hay instanceof String str){
					yield str.contains((CharSequence)needle);
				}
				
				if(hay instanceof List<?> list){
					yield list.contains(needle);
				}
				
				if(hay.getClass().isArray()){
					var size=Array.getLength(hay);
					for(int i=0;i<size;i++){
						var el=Array.get(hay, i);
						if(Objects.equals(el, needle)){
							yield true;
						}
					}
				}
				
				yield false;
			}
			case QueryCheck.Lambda lambda -> lambda.lambda().test(ctx.obj());
			case QueryCheck.CachedMetadata cachedMetadata -> executeCheck(ctx, cachedMetadata.check());
		};
	}
	
}
