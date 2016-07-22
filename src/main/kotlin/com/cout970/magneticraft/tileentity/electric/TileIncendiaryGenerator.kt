package com.cout970.magneticraft.tileentity.electric

import coffee.cypher.mcextlib.extensions.inventories.get
import coffee.cypher.mcextlib.extensions.inventories.set
import com.cout970.magneticraft.api.energy.IElectricNode
import com.cout970.magneticraft.api.energy.impl.ElectricNode
import com.cout970.magneticraft.config.Config
import com.cout970.magneticraft.gui.common.DATA_ID_MACHINE_HEAT
import com.cout970.magneticraft.gui.common.DATA_ID_MACHINE_WORKING
import com.cout970.magneticraft.registry.FLUID_HANDLER
import com.cout970.magneticraft.util.STANDARD_AMBIENT_TEMPERATURE
import com.cout970.magneticraft.util.consumeItem
import com.cout970.magneticraft.util.fluid.Tank
import com.cout970.magneticraft.util.misc.AnimationTimer
import com.cout970.magneticraft.util.misc.IBD
import com.cout970.magneticraft.util.misc.ValueAverage
import com.cout970.magneticraft.util.shouldTick
import com.cout970.magneticraft.util.toKelvinFromCelsius
import net.minecraft.nbt.NBTTagCompound
import net.minecraft.tileentity.TileEntityFurnace
import net.minecraft.util.EnumFacing
import net.minecraftforge.common.capabilities.Capability
import net.minecraftforge.fluids.FluidStack
import net.minecraftforge.fluids.capability.IFluidHandler
import net.minecraftforge.fml.relauncher.Side
import net.minecraftforge.items.ItemStackHandler

/**
 * Created by cout970 on 04/07/2016.
 */

class TileIncendiaryGenerator(
        val tank: Tank = object : Tank(4000) {
            override fun canFillFluidType(fluid: FluidStack): Boolean = fluid.fluid.name == "water"
        }
) : TileElectricBase(), IFluidHandler by tank {

    var mainNode = ElectricNode({ world }, { pos }, capacity = 1.25)
    override val electricNodes: List<IElectricNode>
        get() = listOf(mainNode)
    val inventory = ItemStackHandler(1)
    var maxBurningTime = 0f
    var burningTime = 0f
    var heat = STANDARD_AMBIENT_TEMPERATURE.toFloat()
    val production = ValueAverage()
    var nanoBuckets = 0
    var fanAnimation = AnimationTimer().apply { active = false }


    override fun update() {

        if (!worldObj.isRemote) {
            //consumes fuel
            if (burningTime <= 0 && mainNode.voltage < 120) {
                if (inventory[0] != null) {
                    val time = TileEntityFurnace.getItemBurnTime(inventory[0])
                    if (time > 0) {
                        maxBurningTime = time.toFloat()
                        burningTime = time.toFloat()
                        inventory[0] = consumeItem(inventory[0]!!)
                        markDirty()
                    }
                }
            }
            //burns fuel
            if (burningTime > 0 && heat < MAX_HEAT) {
                val burningSpeed = 8
                burningTime -= burningSpeed
                heat += burningSpeed * FUEL_TO_HEAT
            }
            //makes electricity from heat
            if (heat > STANDARD_AMBIENT_TEMPERATURE + 75 && tank.fluidAmount > 0) {

                val speed = interpolate(heat.toDouble(), STANDARD_AMBIENT_TEMPERATURE, MAX_HEAT - 50)
                val prod = Config.incendiaryGeneratorMaxProduction * speed
                val applied = mainNode.applyPower((1 - interpolate(mainNode.voltage, 120.0, 125.0)) * prod)
                production += applied

                heat -= applied.toFloat() / HEAT_TO_WATTS
                // 1 coal -> 1600 ticks, 1 bucket 1000*1000 nanoBuckets,
                // we want to use 1 bucket ever 8 coal,
                // so every tick uses (1000*1000)/(1600*8) = 78.125 nanoBuckets per fuel
                nanoBuckets += ((applied / FUEL_TO_WATTS) * 78.125).toInt()
                if (nanoBuckets > 1000) {
                    nanoBuckets -= 1000
                    tank.drainInternal(1, true)
                }
            } else if (heat > STANDARD_AMBIENT_TEMPERATURE && tank.fluidAmount > 0) {
//                heat -= 0.109f
            }
            //updates the production counter
            production.tick()

            //sends an update to the client to start/stop the fan animation
            if (shouldTick(200)) {
                val data = IBD()
                data.setBoolean(DATA_ID_MACHINE_WORKING, heat > STANDARD_AMBIENT_TEMPERATURE + 1)
                data.setFloat(DATA_ID_MACHINE_HEAT, heat)
                sendSyncData(data, Side.CLIENT)
            }
        }
        super.update()
    }

    override fun receiveSyncData(data: IBD, side: Side) {
        super.receiveSyncData(data, side)
        if (side == Side.SERVER) {
            data.getBoolean(DATA_ID_MACHINE_WORKING, { fanAnimation.active = it })
            data.getFloat(DATA_ID_MACHINE_WORKING, { heat = it })
        }
    }

    override fun save(): NBTTagCompound = NBTTagCompound().apply {
        setTag("inventory", inventory.serializeNBT())
        setFloat("maxBurningTime", maxBurningTime)
        setFloat("burningTime", burningTime)
        setFloat("heat", heat)
        setTag("tank", NBTTagCompound().apply { tank.writeToNBT(this) })
    }

    override fun load(nbt: NBTTagCompound) {
        inventory.deserializeNBT(nbt.getCompoundTag("inventory"))
        maxBurningTime = nbt.getFloat("maxBurningTime")
        burningTime = nbt.getFloat("burningTime")
        heat = nbt.getFloat("heat")
        tank.readFromNBT(nbt.getCompoundTag("tank"))
    }

    override fun onBreak() {
        super.onBreak()
        if (!worldObj.isRemote) {
            if (inventory[0] != null) {
                dropItem(inventory[0]!!, pos)
            }
        }
    }

    override fun canConnectAtSide(facing: EnumFacing?): Boolean {
        return facing == EnumFacing.UP
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T> getCapability(capability: Capability<T>?, facing: EnumFacing?): T {
        if (capability == FLUID_HANDLER) return this as T
        return super.getCapability(capability, facing)
    }

    override fun hasCapability(capability: Capability<*>?, facing: EnumFacing?): Boolean {
        if (capability == FLUID_HANDLER) return true
        return super.hasCapability(capability, facing)
    }

    companion object {
        val MAX_HEAT = 500.toKelvinFromCelsius()
        //fuel -> burning time -> heat -> electricity
        val FUEL_TO_WATTS = 10// 1 coal = 1600 burning time = 16000RF
        val FUEL_TO_HEAT = 0.5f
        val HEAT_TO_WATTS = FUEL_TO_WATTS / FUEL_TO_HEAT
    }
}