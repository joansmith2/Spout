/*
 * This file is part of Spout.
 *
 * Copyright (c) 2011 Spout LLC <http://www.spout.org/>
 * Spout is licensed under the Spout License Version 1.
 *
 * Spout is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 *
 * In addition, 180 days after any changes are published, you can use the
 * software, incorporating those changes, under the terms of the MIT license,
 * as described in the Spout License Version 1.
 *
 * Spout is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License for
 * more details.
 *
 * You should have received a copy of the GNU Lesser General Public License,
 * the MIT license and the Spout License Version 1 along with this program.
 * If not, see <http://www.gnu.org/licenses/> for the GNU Lesser General Public
 * License and see <http://spout.in/licensev1> for the full license, including
 * the MIT license.
 */
package org.spout.engine.renderer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.google.common.collect.Multimap;
import com.google.common.collect.Ordering;
import com.google.common.collect.TreeMultimap;

import org.spout.api.Client;
import org.spout.api.Spout;
import org.spout.api.geo.World;
import org.spout.api.render.BufferContainer;
import org.spout.api.render.Camera;
import org.spout.api.render.RenderMaterial;
import org.spout.api.render.effect.SnapshotRender;
import org.spout.api.render.shader.Shader;
import org.spout.api.util.map.TInt21TripleObjectHashMapOfMaps;

import org.spout.engine.batcher.ChunkMeshBatchAggregator;
import org.spout.engine.mesh.ChunkMesh;
import org.spout.engine.world.SpoutClientWorld;
import org.spout.math.vector.Vector3f;

public class WorldRenderer {
	public static final long TIME_LIMIT = 2;
	private final BatchGeneratorTask batchGenerator = new BatchGeneratorTask();
	private final ConcurrentLinkedQueue<ChunkMesh> renderChunkMeshBatchQueue = new ConcurrentLinkedQueue<>();
	private final Queue<ChunkMeshBatchAggregator> toUpdate = new LinkedList<>();
	/**
	 * Store ChunkMeshBatchAggregator by BlockFace, RenderMaterial and ChunkMeshBatchAggregator position
	 */
	private final TInt21TripleObjectHashMapOfMaps<RenderMaterial, ChunkMeshBatchAggregator> chunkRenderersByPositions = new TInt21TripleObjectHashMapOfMaps<>();
	private final Multimap<RenderMaterial, ChunkMeshBatchAggregator> chunkRenderers = TreeMultimap.create(RenderMaterial.COMPARATOR, Ordering.arbitrary());
	private SpoutClientWorld currentWorld = null;
	//Benchmark
	public int addedBatch, updatedBatch;
	// Info variables
	private int occludedChunks = 0;
	private int culledChunks = 0;
	private int renderedChunks = 0;
	private int totalChunks = 0;

	public void update(long limit) {
		final SpoutClientWorld world = (SpoutClientWorld) ((Client) Spout.getEngine()).getWorld();

		if (currentWorld != world) {
			if (currentWorld != null) {
				currentWorld.disableRenderQueue();
			}
			world.enableRenderQueue();
			currentWorld = world;
		}

		batchGenerator.run(limit);
	}

	public void render() {
		long start = System.currentTimeMillis();

		update(start + TIME_LIMIT);

		renderChunks();
	}

	public void addMeshToBatchQueue(ChunkMesh mesh) {
		renderChunkMeshBatchQueue.add(mesh);
	}

	public int getOccludedChunks() {
		return occludedChunks;
	}

	public int getCulledChunks() {
		return culledChunks;
	}

	public int getRenderedChunks() {
		return renderedChunks;
	}

	public int getTotalChunks() {
		return totalChunks;
	}

	public int getBatchWaiting() {
		return renderChunkMeshBatchQueue.size();
	}

	private void renderChunks() {
		occludedChunks = 0;
		culledChunks = 0;
		renderedChunks = 0;
		totalChunks = 0;

		/*TODO (maybe): We could optimise iteration and testing frustum on ChunkMeshBatch.
		 * If we sort ChunkMeshBatch by x, y and z, and test only cubo's vertice changing
		 * Example : while we test batch with same x coord, we don't need to test
		 */
		for (Entry<RenderMaterial, Collection<ChunkMeshBatchAggregator>> entry : chunkRenderers.asMap().entrySet()) {
			RenderMaterial material = entry.getKey();

			// TODO: what is the purpose of SnapshotRender as opposed to just passing the RenderMaterial? Future-proofing?
			SnapshotRender snapshotRender = new SnapshotRender(material);
			material.preRender(snapshotRender);
			final Client client = (Client) Spout.getEngine();
			final Camera camera = client.getPlayer().getType(Camera.class);
			final Shader shader = material.getShader();
			shader.setUniform("View", camera.getView());
			shader.setUniform("Projection", camera.getProjection());
			shader.setUniform("Model", ChunkMeshBatchAggregator.model);
			totalChunks += entry.getValue().size();

			Iterator<ChunkMeshBatchAggregator> it = entry.getValue().iterator();
			while (it.hasNext()) {
				ChunkMeshBatchAggregator renderer = it.next();

				if (!renderer.isReady()) {
					continue;
				}

				renderer.preRender();

				// It's hard to look right
				// at the world baby
				// But here's my frustum
				// so cull me maybe?

				if (camera.getFrustum().intersects(renderer)) {
					renderer.render(material);
					renderedChunks++;
				} else {
					culledChunks++;
				}

				renderer.postRender();
			}

			material.postRender(snapshotRender);
		}
	}

	/**
	 * Remove all batch at the specified position
	 */
	private void cleanBatchAggregator(World world, ChunkMesh chunkMesh) {
		Vector3f position = ChunkMeshBatchAggregator.getBaseFromChunkMesh(chunkMesh);
		Map<RenderMaterial, ChunkMeshBatchAggregator> aggregatorPerMaterial = chunkRenderersByPositions.get(position.getFloorX(), position.getFloorY(), position.getFloorZ());

		//Can be null if the thread receive a unload model of a model which has been send previously to load be not done
		if (aggregatorPerMaterial == null) {
			return;
		}

		List<RenderMaterial> toRemoveFromPositions = new ArrayList<>();
		for (Iterator<Entry<RenderMaterial, ChunkMeshBatchAggregator>> it = aggregatorPerMaterial.entrySet().iterator(); it.hasNext(); ) {
			Entry<RenderMaterial, ChunkMeshBatchAggregator> entry = it.next();

			RenderMaterial material = entry.getKey();
			ChunkMeshBatchAggregator batch = entry.getValue();

			if (batch.getWorld() != world) {
				continue;
			}
			Vector3f local = ChunkMeshBatchAggregator.getLocalCoordFromChunkMesh(chunkMesh);
			batch.setSubBatch(null, local.getFloorX(), local.getFloorY(), local.getFloorZ());
			if (!batch.isEmpty()) {
				continue;
			}

			toUpdate.remove(batch);
			chunkRenderers.remove(material, batch);
			toRemoveFromPositions.add(material);
		}

		for (RenderMaterial r : toRemoveFromPositions) {
			ChunkMeshBatchAggregator batch = chunkRenderersByPositions.remove(position.getFloorX(), position.getFloorY(), position.getFloorZ(), r);
			batch.clear();
		}
	}

	private class BatchGeneratorTask {
		public void run(final long limit) {
			addedBatch = 0;
			updatedBatch = 0;

			ChunkMesh mesh;

			// Add ChunkMesh to ChunkMeshBatch
			while (System.currentTimeMillis() <= limit && (mesh = renderChunkMeshBatchQueue.poll()) != null) {
				// Limit on time only applies between loops; if we start a mesh, we finish
				World world = mesh.getWorld();

				if (world != currentWorld) {
					continue;
				}

				if (mesh.isUnloaded()) {
					cleanBatchAggregator(world, mesh);
					continue;
				}

				final Iterator<Entry<RenderMaterial, BufferContainer>> it = mesh.getMaterialsFaces().entrySet().iterator();
				while (it.hasNext()) {
					final Entry<RenderMaterial, BufferContainer> data = it.next();
					addMeshToBatch(mesh, data.getKey(), data.getValue());
				}
			}

			while (System.currentTimeMillis() <= limit && !toUpdate.isEmpty()) {
				final ChunkMeshBatchAggregator batch = toUpdate.peek();

				if (batch.update()) {
					toUpdate.poll();
					batch.setQueued(false);
				}

				updatedBatch++;
			}
		}

		private void addMeshToBatch(ChunkMesh mesh, RenderMaterial material, BufferContainer batchVertex) {
			// In chunk coords
			Vector3f base = ChunkMeshBatchAggregator.getBaseFromChunkMesh(mesh);
			ChunkMeshBatchAggregator batch = chunkRenderersByPositions.get(base.getFloorX(), base.getFloorY(), base.getFloorZ(), material);
			if (batch == null) {
				batch = new ChunkMeshBatchAggregator(mesh.getWorld(), base.getFloorX(), base.getFloorY(), base.getFloorZ(), material);
				// Add to chunkRenderer stores
				chunkRenderers.put(batch.getMaterial(), batch);
				chunkRenderersByPositions.put(base.getFloorX(), base.getFloorY(), base.getFloorZ(), batch.getMaterial(), batch);
			}
			Vector3f localCoord = ChunkMeshBatchAggregator.getLocalCoordFromChunkMesh(mesh);

			batch.setSubBatch(batchVertex, localCoord.getFloorX(), localCoord.getFloorY(), localCoord.getFloorZ());

			if (!batch.isQueued()) {
				addedBatch++;
				toUpdate.add(batch);
				batch.setQueued(true);
			}
		}
	}
}