/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.graph.streaming;

import org.apache.flink.api.common.functions.FoldFunction;
import org.apache.flink.api.common.functions.ReduceFunction;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.api.java.typeutils.ResultTypeQueryable;
import org.apache.flink.api.java.typeutils.TypeExtractor;
import org.apache.flink.graph.Edge;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.WindowedStream;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;

/**
 * A stream of discrete graphs, each maintaining
 * the graph state of the edges contained in the respective window.
 * It is created by calling {@link SimpleEdgeStream#slice()}.
 * The graph slice is keyed by the source or target vertex of the edge stream,
 * so that all edges of a vertex are in the same window.
 *
 * @param <K> the vertex ID type
 * @param <EV> the edge value type
 */
public class GraphWindowStream<K, EV> {

	private WindowedStream<Edge<K, EV>, K, TimeWindow> windowedStream;

	GraphWindowStream(WindowedStream<Edge<K, EV>, K, TimeWindow> window) {
		this.windowedStream = window;
	}

	/**
	 * Performs a neighborhood fold on the graph window stream.
	 * 
	 * @param initialValue
	 * @param foldFunction
	 * @return the result stream after applying the user-defined fold operation on the window
	 */
	public <T> DataStream<T> foldNeighbors(T initialValue, final EdgesFold<K, EV, T> foldFunction) {
		return windowedStream.fold(initialValue, new EdgesFoldFunction<K, EV, T>(foldFunction));
	}

	@SuppressWarnings("serial")
	public static final class EdgesFoldFunction<K, EV, T> implements FoldFunction<Edge<K, EV>, T>,
		ResultTypeQueryable<T> {

		private final EdgesFold<K, EV, T> foldFunction;

		public EdgesFoldFunction(EdgesFold<K, EV, T> foldFunction) {
			this.foldFunction = foldFunction;
		}

		@Override
		public T fold(T accumulator, Edge<K, EV> edge) throws Exception {
			return foldFunction.foldEdges(accumulator, edge.getSource(), edge.getTarget(), edge.getValue());
		}

		@Override
		public TypeInformation<T> getProducedType() {
			return TypeExtractor.createTypeInfo(EdgesFold.class, foldFunction.getClass(), 2,
				null, null);
		}
	}

	/**
	 * Performs an aggregation on the neighboring edges of each vertex on the graph window stream.
	 * <p>
	 * For each vertex, the transformation consecutively calls a
	 * {@link EdgesReduce} function until only a single value for each edge remains.
	 * The {@link EdgesReduce} function combines two edge values into one new value of the same type.
	 * 
	 * @param reduceFunction the aggregation function
	 * @return a result stream of Tuple2, containing one tuple per vertex.
	 * The first field is the vertex ID and the second field is the final value,
	 * after applying the user-defined aggregation operation on the neighborhood.
	 */
	public DataStream<Tuple2<K, EV>> reduceOnEdges(final EdgesReduce<EV> reduceFunction) {
		return windowedStream.reduce(new EdgesReduceFunction<K, EV>(reduceFunction))
			.project(0, 2);
	}

	@SuppressWarnings("serial")
	public static final class EdgesReduceFunction<K, EV> implements ReduceFunction<Edge<K, EV>> {

		private final EdgesReduce<EV> reduceFunction;

		public EdgesReduceFunction(EdgesReduce<EV> reduceFunction) {
			this.reduceFunction = reduceFunction;
		}

		@Override
		public Edge<K, EV> reduce(Edge<K, EV> firstEdge, Edge<K, EV> secondEdge) throws Exception {
			EV reducedValue = this.reduceFunction.reduceEdges(firstEdge.getValue(), secondEdge.getValue());
			firstEdge.setValue(reducedValue);
			return firstEdge;
		}
	}

}
