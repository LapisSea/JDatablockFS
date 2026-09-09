package com.lapissea.jorth;

import com.lapissea.jorth.exceptions.MalformedJorth;
import org.testng.annotations.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class JorthNumericComparisonTests{
	private static final Class<?>[] TYPES = {byte.class, short.class, char.class, int.class, long.class, float.class, double.class};
	
	private static CodeBlock compare(CodeBlock code, int op) throws MalformedJorth{
		return switch(op){
			case 0 -> code.greaterThanOp();
			case 1 -> code.greaterThanOrEqualOp();
			case 2 -> code.lessThanOp();
			case 3 -> code.lessThanOrEqualOp();
			default -> throw new AssertionError(op);
		};
	}
	private static Object[] values(Class<?> type){
		if(type == byte.class) return new Object[]{Byte.MIN_VALUE, (byte)0, (byte)1, Byte.MAX_VALUE};
		if(type == short.class) return new Object[]{Short.MIN_VALUE, (short)0, (short)1, Short.MAX_VALUE};
		if(type == char.class) return new Object[]{(char)0, (char)1, Character.MAX_VALUE};
		if(type == int.class) return new Object[]{Integer.MIN_VALUE, 0, 1, 16777217, Integer.MAX_VALUE};
		if(type == long.class) return new Object[]{Long.MIN_VALUE, 0L, 1L, 16777217L, 9007199254740993L, Long.MAX_VALUE - 1, Long.MAX_VALUE};
		if(type == float.class) return new Object[]{Float.NEGATIVE_INFINITY, -Float.MAX_VALUE, -0.0F, 0.0F, Float.MIN_VALUE, 1F, 16777216F, Float.MAX_VALUE, Float.POSITIVE_INFINITY, Float.NaN};
		return new Object[]{Double.NEGATIVE_INFINITY, -Double.MAX_VALUE, -0.0D, 0.0D, Double.MIN_VALUE, 1D, 9007199254740992D, Double.MAX_VALUE, Double.POSITIVE_INFINITY, Double.NaN};
	}
	private static Number number(Object value){
		return value instanceof Character c? Integer.valueOf(c) : (Number)value;
	}
	private static boolean expected(Class<?> left, Class<?> right, Object a, Object b, int op){
		var x = number(a);
		var y = number(b);
		if(left == double.class || right == double.class){
			return switch(op){case 0 -> x.doubleValue()>y.doubleValue(); case 1 -> x.doubleValue()>=y.doubleValue(); case 2 -> x.doubleValue()<y.doubleValue(); default -> x.doubleValue()<=y.doubleValue();};
		}
		if(left == float.class || right == float.class){
			return switch(op){case 0 -> x.floatValue()>y.floatValue(); case 1 -> x.floatValue()>=y.floatValue(); case 2 -> x.floatValue()<y.floatValue(); default -> x.floatValue()<=y.floatValue();};
		}
		return switch(op){case 0 -> x.longValue()>y.longValue(); case 1 -> x.longValue()>=y.longValue(); case 2 -> x.longValue()<y.longValue(); default -> x.longValue()<=y.longValue();};
	}
	@Test
	public void numericComparisons() throws Exception{
		var cls = TestUtils.generateAndLoadInstanceSimple(TestUtils.autoName(), cd -> {
			for(var left : TYPES) for(var right : TYPES) for(int op = 0; op<4; op++){
				var body = cd.function("compare" + op).staticAcc().arg(left, "a").arg(right, "b").returns(boolean.class).body();
				compare(body.get("a").get("b"), op).returnOp();
			}
		});
		for(var left : TYPES) for(var right : TYPES) for(int op = 0; op<4; op++){
			var method = cls.getMethod("compare" + op, left, right);
			for(var a : values(left)) for(var b : values(right)){
				assertThat(method.invoke(null, a, b)).as("%s %s, op %s: %s, %s", left, right, op, a, b)
				                                      .isEqualTo(expected(left, right, a, b, op));
			}
		}
	}
	@Test
	public void rejectsNonNumericOperandsAndUnderflow(){
		for(int op = 0; op<4; op++){
			final int operation = op;
			for(var type : new Class<?>[]{boolean.class, Object.class, Integer.class, int[].class}){
				for(boolean invalidLeft : new boolean[]{false, true}){
					assertThatThrownBy(() -> {
						var cd = new ClassDefinition(null);
						var body = cd.function("bad").staticAcc().arg(invalidLeft? type : int.class, "a").arg(invalidLeft? int.class : type, "b").body();
						compare(body.get("a").get("b"), operation);
					}).isInstanceOf(MalformedJorth.class).hasMessageContaining("numeric primitives");
				}
			}
			for(int count = 0; count<2; count++){
				final int operands = count;
				assertThatThrownBy(() -> {
					var body = new ClassDefinition(null).function("bad").staticAcc().body();
					if(operands == 1) body.val(1);
					compare(body, operation);
				}).isInstanceOf(MalformedJorth.class);
			}
		}
	}
}
