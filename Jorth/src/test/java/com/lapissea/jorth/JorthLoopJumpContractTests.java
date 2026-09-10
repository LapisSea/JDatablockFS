package com.lapissea.jorth;

import com.lapissea.jorth.exceptions.IllegalConditionalMerge;
import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.jorth.lang.ClassName;
import org.testng.annotations.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class JorthLoopJumpContractTests{
	private static ClassDefinition definition(){
		var cd = new ClassDefinition(null);
		cd.name(ClassName.dotted("test.LoopJumpContracts"));
		return cd;
	}
	private static final CodeArg[] JUMPS = {CodeBlock::breakOp, CodeBlock::continueOp};
	
	@Test
	public void rejectsJumpsOutsideLoop(){
		for(var jump : JUMPS){
			assertThatThrownBy(() -> jump.accept(definition().function("run").staticAcc().body()))
				.isInstanceOf(MalformedJorth.class).hasMessageContaining("requires an enclosing loop");
			assertThatThrownBy(() -> definition().function("run").staticAcc().body().scope(jump))
				.isInstanceOf(MalformedJorth.class).hasMessageContaining("requires an enclosing loop");
		}
	}
	
	@Test
	public void jumpDestinationsValidateEvenThoughPathDoesNotFallThrough(){
		for(var jump : JUMPS){
			for(CodeArg extra : new CodeArg[]{c -> c.val(1), c -> c.val(2L), c -> c.val(3D)}){
				assertThatThrownBy(() -> definition().function("run").staticAcc().body().loop(c -> c.val(true), b -> {
					extra.accept(b);
					jump.accept(b);
				})).isInstanceOf(IllegalConditionalMerge.class).hasMessageContaining("loop");
			}
		}
	}
	
	@Test
	public void jumpsEndTheirBlockAndDoNotLeakLoopScope() throws Exception{
		for(var jump : JUMPS){
			var outer = definition().function("run").staticAcc().body();
			outer.loop(c -> c.val(false), b -> {
				jump.accept(b);
				assertThatThrownBy(() -> b.val(1)).isInstanceOf(MalformedJorth.class).hasMessageContaining("terminated");
			});
			assertThatThrownBy(() -> jump.accept(outer)).isInstanceOf(MalformedJorth.class).hasMessageContaining("requires an enclosing loop");
		}
	}
	
	@Test(timeOut = 10000)
	public void jumpsPreserveNonemptyStackAndDeferredBranches() throws Exception{
		var cls = TestUtils.generateAndLoadInstanceSimple(TestUtils.autoName(), cd -> {
			cd.function("run").staticAcc().returns(double.class).body()
			  .var(int.class, "i").val(0).set("i").val(123L).val(1.25D)
			  .loop(c -> c.get("i").val(10).lessThanOp(), b -> {
				  b.get("i").add(1).set("i");
				  b.lazyBlock(lazy -> lazy.get("i").val(2).lessThanOp().ifTrue(CodeBlock::continueOp));
				  b.lazyBlock(lazy -> lazy.get("i").val(2).ifEquality(CodeBlock::breakOp));
				  b.newObj(AssertionError.class).throwOp();
			  }).swap().pop().returnOp();
		});
		assertThat(cls.getMethod("run").invoke(null)).isEqualTo(1.25D);
	}
	
	@Test
	public void deferredJumpsStillValidateDestinationStacks() throws Exception{
		for(var jump : JUMPS){
			var cd = definition();
			cd.function("run").staticAcc().body().loop(c -> c.val(false), b -> {
				b.lazyBlock(lazy -> lazy.val(true).ifTrue(branch -> {
					branch.val(1L);
					jump.accept(branch);
				}));
			});
			assertThatThrownBy(cd::getClassFile).isInstanceOf(IllegalConditionalMerge.class);
		}
	}
}
