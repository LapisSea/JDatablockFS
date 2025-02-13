package com.lapissea.dfs.objects.collections;

import java.util.Objects;

sealed interface BucketResult<T>{
	record EmptyIndex<T>(long index) implements BucketResult<T>{
		public EmptyIndex{
			if(index<0) throw new IllegalArgumentException("index should not be negative");
		}
	}
	
	record TailNode<T>(IONode<T> node) implements BucketResult<T>{
		public TailNode{ Objects.requireNonNull(node); }
	}
	
	record EqualsResult<T>(long index, IONode<T> previous, IONode<T> node) implements BucketResult<T>{
		public EqualsResult{ Objects.requireNonNull(node); }
	}
}
