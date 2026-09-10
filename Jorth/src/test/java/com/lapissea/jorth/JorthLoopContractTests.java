package com.lapissea.jorth;

import com.lapissea.jorth.exceptions.IllegalConditionalMerge;
import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.jorth.lang.ClassName;
import org.testng.annotations.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class JorthLoopContractTests{
	private static ClassDefinition clazz(){
		var cd = new ClassDefinition(null);
		cd.name(ClassName.dotted("test.LoopContracts"));
		return cd;
	}
	
	@Test
	public void rejectsInvalidConditionResults(){
		for(CodeArg check : new CodeArg[]{
			c -> { },
			c -> c.val(1),
			c -> c.val(1L),
			c -> c.val("true"),
			c -> c.val(true).val(false)
		}){
			assertThatThrownBy(() -> {
				clazz().function("run")
				       .staticAcc()
				       .body()
				       .loop(check, b -> { });
			}).isInstanceOf(IllegalConditionalMerge.class)
			  .hasMessageContaining("loop condition");
		}
	}
	
	@Test
	public void rejectsBodyStackLeaks(){
		for(CodeArg body : new CodeArg[]{
			b -> b.val(1),
			b -> b.val(1L),
			b -> b.val(1D)
		}){
			assertThatThrownBy(() -> {
				clazz().function("run")
				       .staticAcc()
				       .body()
				       .loop(c -> c.val(true), body);
			}).isInstanceOf(IllegalConditionalMerge.class)
			  .hasMessageContaining("body back edge");
		}
	}
	
	@Test
	public void rejectsConsumptionOfInheritedStack(){
		assertThatThrownBy(() -> {
			clazz().function("run")
			       .staticAcc()
			       .body()
			       .val(1L)
			       .loop(c -> c.pop().val(true), b -> { });
		}).isInstanceOf(MalformedJorth.class)
		  .hasMessageContaining("outside the code path");
		assertThatThrownBy(() -> {
			clazz().function("run")
			       .staticAcc()
			       .body()
			       .val(1D)
			       .loop(c -> c.val(true), CodeBlock::pop);
		}).isInstanceOf(MalformedJorth.class)
		  .hasMessageContaining("outside the code path");
	}
	
	@Test
	public void rejectsConditionsWithNoNormalCompletion(){
		for(CodeArg terminate : new CodeArg[]{
			CodeBlock::returnOp,
			c -> c.nullVal(RuntimeException.class).throwOp()
		}){
			for(CodeArg check : new CodeArg[]{
				terminate,
				c -> c.scope(terminate),
				c -> c.val(true).ifTrue(terminate).elseRun(terminate)
			}){
				assertThatThrownBy(() -> {
					clazz().function("run")
					       .staticAcc()
					       .body()
					       .loop(check, b -> { });
				}).isInstanceOf(MalformedJorth.class)
				  .hasMessageContaining("Loop check must not terminate");
			}
		}
	}
	
	@Test(timeOut = 10000)
	public void conditionalReturnOrThrowCanExitCondition() throws Exception{
		for(boolean deferred : new boolean[]{false, true}){
			var cls = TestUtils.generateAndLoadInstanceSimple(TestUtils.autoName(), cd -> {
				cd.function("run")
				  .staticAcc()
				  .arg(int.class, "mode")
				  .returns(int.class)
				  .body()
				  .var(int.class, "i").val(0).set("i")
				  .loop(c -> {
					  CodeArg earlyExit = b -> {
						  b.get("mode").val(1).ifEquality(ret -> ret.val(7).returnOp());
						  b.get("mode").val(2).ifEquality(thr -> thr.throwNew(IllegalStateException.class));
					  };
					  if(deferred) c.lazyBlock(earlyExit);
					  else c.scope(earlyExit);
					  c.get("i")
					   .val(3)
					   .lessThanOp();
				  }, body -> {
					  body.inc("i");
				  })
				  .get("i").returnOp();
			});
			var run = cls.getMethod("run", int.class);
			assertThat(run.invoke(null, 0)).isEqualTo(3);
			assertThat(run.invoke(null, 1)).isEqualTo(7);
			assertThatThrownBy(() -> run.invoke(null, 2)).hasCauseInstanceOf(IllegalStateException.class);
		}
	}
	
	@Test(timeOut = 10000)
	public void preservesNonemptyStack() throws Exception{
		var cls = TestUtils.generateAndLoadInstanceSimple(TestUtils.autoName(), cd -> {
			cd.function("run")
			  .staticAcc()
			  .arg(int.class, "count")
			  .returns(String.class)
			  .body()
			  .var(int.class, "i").val(0).set("i")
			  .val("prefix").val(17).val(1234567890123L).val(1.25D)
			  .loop(
				  c -> c.get("i").get("count").lessThanOp(),
				  b -> b.get("i").add(1).set("i")
			  )
			  .var(double.class, "d").set("d")
			  .var(long.class, "l").set("l")
			  .var(int.class, "n").set("n")
			  .var(String.class, "s").set("s")
			  .get("s").call("concat", a -> a.call(Integer.class, "toString", v -> v.get("n")))
			  .call("concat", a -> a.call(Long.class, "toString", v -> v.get("l")))
			  .call("concat", a -> a.call(Double.class, "toString", v -> v.get("d")))
			  .call("concat", a -> a.call(Integer.class, "toString", v -> v.get("i"))).returnOp();
		});
		var run = cls.getMethod("run", int.class);
		for(int count : new int[]{0, 1, 5}){
			assertThat(run.invoke(null, count)).isEqualTo("prefix1712345678901231.25" + count);
		}
	}
}
