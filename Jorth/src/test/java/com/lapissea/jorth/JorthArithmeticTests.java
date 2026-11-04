package com.lapissea.jorth;

import com.lapissea.jorth.exceptions.MalformedJorth;
import org.testng.annotations.Test;

import java.lang.reflect.InvocationTargetException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class JorthArithmeticTests{
	private static final Class<?>[] TYPES = {byte.class, short.class, char.class, int.class, long.class, float.class, double.class};
	private static Class<?> resultType(Class<?> type){
		return type == byte.class || type == short.class || type == char.class? int.class : type;
	}
	private static Number number(Object value){ return value instanceof Character c? Integer.valueOf(c) : (Number)value; }
	private static Object[] samples(Class<?> type){
		if(type == byte.class) return new Object[]{(byte)-11, (byte)3};
		if(type == short.class) return new Object[]{(short)-11, (short)3};
		if(type == char.class) return new Object[]{(char)11, (char)3};
		if(type == int.class) return new Object[]{Integer.MIN_VALUE, -11, -3, -1, 0, 3, Integer.MAX_VALUE};
		if(type == long.class) return new Object[]{Long.MIN_VALUE, -11L, -3L, -1L, 0L, 3L, Long.MAX_VALUE};
		if(type == float.class) return new Object[]{-11.5F, -0F, 0F, 3.25F, Float.POSITIVE_INFINITY, Float.NaN};
		return new Object[]{-11.5D, -0D, 0D, 3.25D, Double.POSITIVE_INFINITY, Double.NaN};
	}
	private static CodeBlock operation(CodeBlock body, int op) throws MalformedJorth{
		return switch(op){ case 0 -> body.add(); case 1 -> body.sub(); case 2 -> body.mul(); case 3 -> body.div(); default -> body.mod(); };
	}
	private static CodeBlock constant(CodeBlock body, int op, Number value) throws MalformedJorth{
		return switch(value){
			case Integer v -> switch(op){ case 0 -> body.add(v); case 1 -> body.sub(v); case 2 -> body.mul(v); case 3 -> body.div(v); default -> body.mod(v); };
			case Long v -> switch(op){ case 0 -> body.add(v); case 1 -> body.sub(v); case 2 -> body.mul(v); case 3 -> body.div(v); default -> body.mod(v); };
			case Float v -> switch(op){ case 0 -> body.add(v); case 1 -> body.sub(v); case 2 -> body.mul(v); case 3 -> body.div(v); default -> body.mod(v); };
			case Double v -> switch(op){ case 0 -> body.add(v); case 1 -> body.sub(v); case 2 -> body.mul(v); case 3 -> body.div(v); default -> body.mod(v); };
			default -> throw new AssertionError(value);
		};
	}
	private static Object expected(Class<?> type, Object a, Object b, int op){
		var x = number(a); var y = number(b);
		if(type == double.class){ double l=x.doubleValue(), r=y.doubleValue(); return switch(op){ case 0 -> l+r; case 1 -> l-r; case 2 -> l*r; case 3 -> l/r; default -> l%r; }; }
		if(type == float.class){ float l=x.floatValue(), r=y.floatValue(); return switch(op){ case 0 -> l+r; case 1 -> l-r; case 2 -> l*r; case 3 -> l/r; default -> l%r; }; }
		if(type == long.class){ long l=x.longValue(), r=y.longValue(); return switch(op){ case 0 -> l+r; case 1 -> l-r; case 2 -> l*r; case 3 -> l/r; default -> l%r; }; }
		int l=x.intValue(), r=y.intValue(); return switch(op){ case 0 -> l+r; case 1 -> l-r; case 2 -> l*r; case 3 -> l/r; default -> l%r; };
	}
	private static Object negative(Class<?> type, Object value){
		var n=number(value);
		if(type==double.class) return -n.doubleValue();
		if(type==float.class) return -n.floatValue();
		if(type==long.class) return -n.longValue();
		return -n.intValue();
	}

	@Test
	public void stackArithmeticMatchesJava() throws Exception{
		var cls = TestUtils.generateAndLoadInstanceSimple(TestUtils.autoName(), cd -> {
			for(var type : TYPES) for(int op=0; op<5; op++){
				var body = cd.function("op"+op).staticAcc().arg(type,"a").arg(type,"b").returns(resultType(type)).body();
				operation(body.get("a").get("b"),op).returnOp();
			}
		});
		for(var type : TYPES) for(int op=0; op<5; op++){
			var method=cls.getMethod("op"+op,type,type);
			for(var a : samples(type)) for(var b : samples(type)){
				if(op>=3 && type!=float.class && type!=double.class && number(b).longValue()==0){
					assertThatThrownBy(() -> method.invoke(null,a,b)).isInstanceOf(InvocationTargetException.class).hasCauseInstanceOf(ArithmeticException.class);
				}else assertThat(method.invoke(null,a,b)).as("%s operation %s: %s, %s",type,op,a,b).isEqualTo(expected(type,a,b,op));
			}
		}
	}
	@Test
	public void constantOverloadsAndIntAdaptation() throws Exception{
		Number[] constants={-3, -3L, -3.25F, -3.25D};
		var cls=TestUtils.generateAndLoadInstanceSimple(TestUtils.autoName(),cd -> {
			for(var type : TYPES) for(int kind=0; kind<constants.length; kind++){
				if(kind!=0 && resultType(type)!=new Class<?>[]{int.class,long.class,float.class,double.class}[kind]) continue;
				for(int op=0; op<5; op++) constant(cd.function("c"+kind+op).staticAcc().arg(type,"a").returns(resultType(type)).body().get("a"),op,constants[kind]).returnOp();
			}
		});
		for(var type : TYPES) for(int kind=0; kind<constants.length; kind++){
			if(kind!=0 && resultType(type)!=new Class<?>[]{int.class,long.class,float.class,double.class}[kind]) continue;
			for(int op=0; op<5; op++) for(var a : samples(type)) assertThat(cls.getMethod("c"+kind+op,type).invoke(null,a)).isEqualTo(expected(type,a,constants[kind],op));
		}
	}
	@Test
	public void rejectsInvalidOperandsAndUnderflow(){
		for(int op=0; op<5; op++){
			final int operation=op;
			for(var left : new Class<?>[]{boolean.class,Object.class,Integer.class,int[].class,int.class,long.class,float.class,double.class})
				for(var right : new Class<?>[]{boolean.class,Object.class,Integer.class,int[].class,int.class,long.class,float.class,double.class}){
					if(left==right && (left==int.class || left==long.class || left==float.class || left==double.class)) continue;
					assertThatThrownBy(() -> {
						var body=new ClassDefinition(null).function("bad").staticAcc().arg(left,"a").arg(right,"b").body();
						operation(body.get("a").get("b"),operation);
					}).isInstanceOf(MalformedJorth.class);
				}
			for(int count=0; count<2; count++){
				final int operands=count;
				assertThatThrownBy(() -> { var body=new ClassDefinition(null).function("bad").staticAcc().body(); if(operands==1) body.val(1); operation(body,operation); }).isInstanceOf(MalformedJorth.class);
			}
			for(Number value : new Number[]{0,0L,0F,0D}){
				assertThatThrownBy(() -> constant(new ClassDefinition(null).function("bad").staticAcc().body(),operation,value)).isInstanceOf(MalformedJorth.class);
				for(var type : new Class<?>[]{boolean.class,Object.class,int.class,long.class,float.class,double.class}){
					if(value instanceof Integer && type!=boolean.class && type!=Object.class) continue;
					if(value instanceof Long && type==long.class || value instanceof Float && type==float.class || value instanceof Double && type==double.class) continue;
					assertThatThrownBy(() -> constant(new ClassDefinition(null).function("bad").staticAcc().arg(type,"a").body().get("a"),operation,value)).isInstanceOf(MalformedJorth.class);
				}
			}
		}
	}
	@Test
	public void negationAndIntCategoryMixing() throws Exception{
		var cls=TestUtils.generateAndLoadInstanceSimple(TestUtils.autoName(),cd -> {
			for(var type : TYPES) cd.function("neg").staticAcc().arg(type,"a").returns(resultType(type)).body().get("a").neg().returnOp();
			for(var left : new Class<?>[]{byte.class,short.class,char.class,int.class}) for(var right : new Class<?>[]{byte.class,short.class,char.class,int.class})
				operation(cd.function("mix").staticAcc().arg(left,"a").arg(right,"b").returns(int.class).body().get("a").get("b"),0).returnOp();
		});
		for(var type : TYPES) for(var a : samples(type)) assertThat(cls.getMethod("neg",type).invoke(null,a)).isEqualTo(negative(type,a));
		for(var left : new Class<?>[]{byte.class,short.class,char.class,int.class}) for(var right : new Class<?>[]{byte.class,short.class,char.class,int.class}){
			var a=samples(left)[0]; var b=samples(right)[0];
			assertThat(cls.getMethod("mix",left,right).invoke(null,a,b)).isEqualTo(number(a).intValue()+number(b).intValue());
		}
		for(var type : new Class<?>[]{boolean.class,Object.class,Integer.class,int[].class}) assertThatThrownBy(() -> new ClassDefinition(null).function("bad").staticAcc().arg(type,"a").body().get("a").neg()).isInstanceOf(MalformedJorth.class);
		assertThatThrownBy(() -> new ClassDefinition(null).function("bad").staticAcc().body().neg()).isInstanceOf(MalformedJorth.class);
	}

	@Test
	public void failedValidationPreservesOperands() throws Exception{
		var cls=TestUtils.generateAndLoadInstanceSimple(TestUtils.autoName(),cd -> {
			var body=cd.function("recover").staticAcc().returns(int.class).body().val(7);
			assertThatThrownBy(body::add).isInstanceOf(MalformedJorth.class);
			assertThatThrownBy(() -> body.mul(2L)).isInstanceOf(MalformedJorth.class);
			body.val(3).sub().returnOp();
			var scoped=cd.function("scoped").staticAcc().returns(int.class).body().val(7);
			scoped.scope(child -> {
				child.val(3);
				assertThatThrownBy(child::add).isInstanceOf(MalformedJorth.class);
			});
			scoped.sub().returnOp();
		});
		assertThat(cls.getMethod("recover").invoke(null)).isEqualTo(4);
		assertThat(cls.getMethod("scoped").invoke(null)).isEqualTo(4);
	}

	@Test
	public void constantZeroAndSurroundingStack() throws Exception{
		Class<?>[] types={int.class,long.class,float.class,double.class};
		Number[] zeros={0,0L,0F,0D};
		var cls=TestUtils.generateAndLoadInstanceSimple(TestUtils.autoName(),cd -> {
			for(int kind=0; kind<types.length; kind++) for(int op=3; op<5; op++)
				constant(cd.function("zero"+op).staticAcc().arg(types[kind],"a").returns(types[kind]).body().get("a"),op,zeros[kind]).returnOp();
			for(int op=0; op<5; op++){
				var body=cd.function("surround"+op).staticAcc().returns(int.class).body().val(100).val(11).val(3);
				operation(body,op).add().returnOp();
			}
		});
		for(int kind=0; kind<types.length; kind++) for(int op=3; op<5; op++){
			var method=cls.getMethod("zero"+op,types[kind]);
			Object a=samples(types[kind])[1];
			if(kind<2) assertThatThrownBy(() -> method.invoke(null,a)).hasCauseInstanceOf(ArithmeticException.class);
			else for(var value : samples(types[kind])) assertThat(method.invoke(null,value)).isEqualTo(expected(types[kind],value,zeros[kind],op));
		}
		for(int op=0; op<5; op++) assertThat(cls.getMethod("surround"+op).invoke(null)).isEqualTo(100+(Integer)expected(int.class,11,3,op));
	}
	@Test
	public void rejectsArithmeticAfterTermination() throws Exception{
		for(int op=0; op<5; op++){
			final int operation=op;
			var body=new ClassDefinition(null).function("done").staticAcc().body();
			body.returnOp();
			assertThatThrownBy(() -> operation(body,operation)).isInstanceOf(MalformedJorth.class).hasMessageContaining("terminated");
			assertThatThrownBy(() -> constant(body,operation,1)).isInstanceOf(MalformedJorth.class).hasMessageContaining("terminated");
		}
	}
}
