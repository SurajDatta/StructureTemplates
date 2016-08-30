/*
 * Copyright 2016 MovingBlocks
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.terasology.structureTemplates.internal.systems;

import java.util.HashMap;
import java.util.Map;
import com.google.common.collect.Maps;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terasology.entitySystem.entity.EntityRef;
import org.terasology.entitySystem.event.EventPriority;
import org.terasology.entitySystem.event.ReceiveEvent;
import org.terasology.entitySystem.systems.BaseComponentSystem;
import org.terasology.entitySystem.systems.RegisterMode;
import org.terasology.entitySystem.systems.RegisterSystem;
import org.terasology.logic.common.ActivateEvent;
import org.terasology.logic.delay.DelayManager;
import org.terasology.logic.delay.DelayedActionTriggeredEvent;
import org.terasology.logic.location.LocationComponent;
import org.terasology.math.Region3i;
import org.terasology.math.Side;
import org.terasology.math.geom.Vector3f;
import org.terasology.math.geom.Vector3i;
import org.terasology.registry.In;
import org.terasology.structureTemplates.components.GrowBlockRegionsComponent;
import org.terasology.structureTemplates.components.SpawnBlockRegionsComponent;
import org.terasology.structureTemplates.components.SpawnBlockRegionsComponent.RegionToFill;
import org.terasology.structureTemplates.components.SpawnStructureActionComponent;
import org.terasology.structureTemplates.components.StructureTemplateComponent;
import org.terasology.structureTemplates.events.CheckSpawnConditionEvent;
import org.terasology.structureTemplates.events.SpawnStructureEvent;
import org.terasology.structureTemplates.internal.events.StructureSpawnFailedEvent;
import org.terasology.structureTemplates.util.transform.BlockRegionMovement;
import org.terasology.structureTemplates.util.transform.BlockRegionTransform;
import org.terasology.structureTemplates.util.transform.BlockRegionTransformationList;
import org.terasology.structureTemplates.util.transform.HorizontalBlockRegionRotation;
import org.terasology.world.WorldProvider;
import org.terasology.world.block.Block;
import org.terasology.world.block.BlockComponent;
import org.terasology.world.block.entity.placement.PlaceBlocks;

/**
 * Spawns structures when entities with certain components receive a {@link SpawnStructureEvent}.
 * e.g. the entity that receives a {@link SpawnStructureEvent} has a {@link SpawnBlockRegionsComponent} then
 * the regions specified by that component will be filled with the specified block types.
 * <p>
 * Handles also the activation of items with the {@link SpawnStructureActionComponent}
 */
@RegisterSystem(RegisterMode.AUTHORITY)
public class StructureSpawnServerSystem extends BaseComponentSystem {
    private static final Logger logger = LoggerFactory.getLogger(StructureSpawnServerSystem.class);

    private static final String GROW_STRUCTURE_ACTION_ID = "structureTemplates:growStructureEvent";

    @In
    private WorldProvider worldProvider;


    @In
    private DelayManager delayManager;

    @ReceiveEvent
    public void onSpawnBlockRegions(SpawnStructureEvent event, EntityRef entity,
                                    SpawnBlockRegionsComponent spawnBlockRegionComponent) {
        long startTime = System.currentTimeMillis();
        BlockRegionTransform transformation = event.getTransformation();

        Map<Vector3i, Block> blocksToPlace = new HashMap<>();
        for (RegionToFill regionToFill : spawnBlockRegionComponent.regionsToFill) {
            Block block = regionToFill.blockType;

            Region3i region = regionToFill.region;
            region = transformation.transformRegion(region);
            block = transformation.transformBlock(block);

            for (Vector3i pos : region) {
                blocksToPlace.put(pos, block);
            }
        }

        worldProvider.setBlocks(blocksToPlace);
        long endTime = System.currentTimeMillis();
        long delta = endTime - startTime;
        if (delta > 20) {
            logger.warn("Structure of type {} took {} ms to spawn", entity.getParentPrefab().getName(), delta);
        }
    }

    @ReceiveEvent(priority = EventPriority.PRIORITY_HIGH)
    public void onGrowBlockRegions(SpawnStructureEvent event, EntityRef entity,
                                   GrowBlockRegionsComponent growBlockRegionsComponent, SpawnBlockRegionsComponent spawnBlockRegionsComponent) {


        BlockRegionTransform transformation = event.getTransformation();

        Map<Integer, Map<Vector3i, Block>> blocksPerLayer = Maps.newHashMap();
        for (RegionToFill regionToFill : spawnBlockRegionsComponent.regionsToFill) {
            Block block = regionToFill.blockType;

            Region3i region = regionToFill.region;
            region = transformation.transformRegion(region);
            block = transformation.transformBlock(block);

            for (Vector3i pos : region) {
                final int y = pos.getY();
                if (!blocksPerLayer.containsKey(y)) {
                    blocksPerLayer.put(y, Maps.newHashMap());
                }
                blocksPerLayer.get(y).put(pos, block);
            }
        }

        growBlockRegionsComponent.blocksPerLayer = blocksPerLayer;
        growBlockRegionsComponent.iter = 1;
        entity.saveComponent(growBlockRegionsComponent);

        delayManager.addDelayedAction(entity, GROW_STRUCTURE_ACTION_ID, 0);
        event.consume();    // structure should not be build by any other event handler.
    }

    @ReceiveEvent
    public void onDelayedTriggeredEvent(DelayedActionTriggeredEvent event, EntityRef entity,
                                        GrowBlockRegionsComponent growBlockRegionsComponent, SpawnBlockRegionsComponent spawnBlockRegionsComponent) {

        Map<Integer, Map<Vector3i, Block>> blocksPerLayer = growBlockRegionsComponent.blocksPerLayer;

        // place the first 'iter' layers on the ground, starting from the top
        final int min = Collections.min(blocksPerLayer.keySet());
        final int max = Collections.max(blocksPerLayer.keySet());
        final int iter = growBlockRegionsComponent.iter;

        Map<Vector3i, Block> blocksToPlace = Maps.newHashMap();

        for (int i = 0; i < iter; i++) {
            Map<Vector3i, Block> currentLayer = blocksPerLayer.get(max - i);
            final int y = min + iter - i - 1;
            currentLayer.forEach((pos, block) -> blocksToPlace.put(new Vector3i(pos.x, y, pos.z), block));
        }

        PlaceBlocks blockEvent = new PlaceBlocks(blocksToPlace);
        worldProvider.getWorldEntity().send(blockEvent);

        if (min + iter <= max) {
            growBlockRegionsComponent.iter = growBlockRegionsComponent.iter + 1;
            entity.saveComponent(growBlockRegionsComponent);
            delayManager.addDelayedAction(entity, GROW_STRUCTURE_ACTION_ID, 200);
        }
    }

    @ReceiveEvent
    public void onActivate(ActivateEvent event, EntityRef entity,
                           SpawnStructureActionComponent spawnActionComponent,
                           StructureTemplateComponent structureTemplateComponent) {
        // activation with error hides error:
        if (spawnActionComponent.unconfirmSpawnErrorRegion != null) {
            spawnActionComponent.unconfirmSpawnErrorRegion = null;
            entity.saveComponent(spawnActionComponent);
            return;
        }

        EntityRef target = event.getTarget();
        BlockComponent blockComponent = target.getComponent(BlockComponent.class);
        if (blockComponent == null) {
            return;
        }

        LocationComponent characterLocation = event.getInstigator().getComponent(LocationComponent.class);
        Vector3f directionVector = characterLocation.getWorldDirection();

        Side facedDirection = Side.inHorizontalDirection(directionVector.getX(), directionVector.getZ());
        Side wantedFrontOfStructure = facedDirection.reverse();

        Side frontOfStructure = structureTemplateComponent.front;


        BlockRegionTransform blockRegionTransform = createBlockRegionTransformForCharacterTargeting(frontOfStructure,
                wantedFrontOfStructure, blockComponent.getPosition());
        CheckSpawnConditionEvent checkSpawnEvent = new CheckSpawnConditionEvent(blockRegionTransform);
        entity.send(checkSpawnEvent);
        if (checkSpawnEvent.isPreventSpawn()) {
            spawnActionComponent.unconfirmSpawnErrorRegion = checkSpawnEvent.getSpawnPreventingRegion();
            entity.saveComponent(spawnActionComponent);
            entity.send(new StructureSpawnFailedEvent(checkSpawnEvent.getFailedSpawnCondition(),
                    checkSpawnEvent.getSpawnPreventingRegion()));
            return;
        }

        entity.send(new SpawnStructureEvent(blockRegionTransform));

    }

    public static BlockRegionTransform createBlockRegionTransformForCharacterTargeting(
            Side fromSide, Side toSide, Vector3i target) {
        BlockRegionTransformationList transformList = new BlockRegionTransformationList();
        transformList.addTransformation(
                HorizontalBlockRegionRotation.createRotationFromSideToSide(fromSide, toSide));
        transformList.addTransformation(new BlockRegionMovement(target));
        return transformList;
    }

}
