package com.lapissea.jorth;

import com.lapissea.jorth.lang.type.GenericType;
import org.testng.annotations.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class JorthBranchSnapshotTests{
	@Test
	public void conditionIsConsumedBeforeCallbackAndNestedMergesStayStable() throws Exception{
		var captured = new CodeBlock[1];
		var cls = TestUtils.generateAndLoadInstanceSimple(TestUtils.autoName(), cd -> {
			var code = cd.function("run").staticAcc()
			             .arg(boolean.class, "a")
			             .arg(boolean.class, "b")
			             .returns(long.class)
			             .body();
			code.val(99L).get("a").ifTrue(yes -> {
				captured[0] = yes;
				assertThat(yes.stackView()).containsExactly(GenericType.LONG);
				yes.get("b")
				   .ifTrue(inner -> inner.val(1L))
				   .elseRun(inner -> inner.val(2L));
			}).elseRun(no -> {
				no.get("b")
				  .ifTrue(inner -> inner.val(3L))
				  .elseRun(inner -> inner.val(4L));
			});
			code.mergeBranch(); // just for testing, should not be done manually
			assertThat(code.stackView()).containsExactly(GenericType.LONG, GenericType.LONG);
			code.swap()
			    .pop()
			    .returnOp();
			assertThat(captured[0].stackView()).containsExactly(GenericType.LONG, GenericType.LONG);
			assertThatThrownBy(() -> captured[0].val(5L)).isInstanceOf(IllegalAccessError.class);
		});
		var run = cls.getMethod("run", boolean.class, boolean.class);
		assertThat(run.invoke(null, true, true)).isEqualTo(1L);
		assertThat(run.invoke(null, true, false)).isEqualTo(2L);
		assertThat(run.invoke(null, false, true)).isEqualTo(3L);
		assertThat(run.invoke(null, false, false)).isEqualTo(4L);
	}
	
	@Test
	public void returningPathDoesNotConstrainContinuation() throws Exception{
		var cls = TestUtils.generateAndLoadInstanceSimple(TestUtils.autoName(), cd -> {
			cd.function("run").staticAcc()
			  .arg(boolean.class, "early")
			  .returns(int.class)
			  .body()
			  .get("early").ifTrue(yes -> yes.val(7).returnOp()).elseRun(no -> no.val(8))
			  .add(1).returnOp();
		});
		var run = cls.getMethod("run", boolean.class);
		assertThat(run.invoke(null, true)).isEqualTo(7);
		assertThat(run.invoke(null, false)).isEqualTo(9);
	}
}
