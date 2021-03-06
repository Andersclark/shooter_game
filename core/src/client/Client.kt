package client

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.TextureAtlas
import com.badlogic.gdx.utils.Array
import com.mygdx.game.model.agent.Agent
import com.mygdx.game.model.agent.Opponent
import com.mygdx.game.model.agent.Player
import com.mygdx.game.model.explosion.BarrelExplosion
import com.mygdx.game.model.explosion.BazookaExplosion
import com.mygdx.game.model.explosion.ExplosionType
import com.mygdx.game.model.obstacles.ExplosiveBarrel
import com.mygdx.game.model.obstacles.Wall
import com.mygdx.game.model.pickup.*
import com.mygdx.game.model.projectile.*
import com.mygdx.game.model.weapon.Bazooka
import com.mygdx.game.model.weapon.MachineGun
import com.mygdx.game.model.weapon.Pistol
import com.mygdx.game.model.weapon.Shotgun
import com.mygdx.game.settings.*
import com.mygdx.game.util.getZonesForRectangle
import io.socket.client.IO
import io.socket.client.Socket
import ktx.assets.pool
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class Client {
    companion object {
        lateinit var socket: Socket
        val pickups = ConcurrentHashMap<String, Pickup>()

        lateinit var projectileTexture: Texture
        lateinit var pistolTexture: Texture
        lateinit var machineGunTexture: Texture
        lateinit var shotgunTexture: Texture
        lateinit var bazookaTexture: Texture
        var shouldPlayReload = false

        private lateinit var player: Player

        val projectiles = ConcurrentHashMap<String, Projectile>()
        val pistolProjectilePool = pool { PistolProjectile(texture = projectileTexture) }
        val machineGunProjectilePool = pool { MachineGunProjectile(texture = projectileTexture) }
        val shotgunProjectilePool = pool { ShotgunProjectile(texture = projectileTexture) }
        val bazookaProjectilePool = pool { BazookaProjectile(texture = projectileTexture) }

        lateinit var bazookaExplosionTextureAtlas: TextureAtlas
        val bazookaExplosionPool = pool { BazookaExplosion(textureAtlas = bazookaExplosionTextureAtlas) }
        val barrelExplosionPool = pool { BarrelExplosion(textureAtlas = bazookaExplosionTextureAtlas) }
        val bazookaExplosions = Array<BazookaExplosion>()
        val barrelExplosions = Array<BarrelExplosion>()

        private var pistolPickupPool = pool { PistolPickup(texture = pistolTexture) }
        private var machineGunPickupPool = pool { MachineGunPickup(texture = machineGunTexture) }
        private var shotgunPickupPool = pool { ShotgunPickup(texture = shotgunTexture) }
        private var bazookaPickupPool = pool { BazookaPickup(texture = bazookaTexture) }

        private var explosiveBarrelPool = pool { ExplosiveBarrel(texture = explosiveBarrelTexture) }

        var explosiveBarrels = ConcurrentHashMap<String, ExplosiveBarrel>()

        val opponents = ConcurrentHashMap<String, Opponent>()
        private lateinit var playerTextures: TextureAtlas
        private lateinit var healthBarTexture: Texture
        private lateinit var wallMatrix: HashMap<String, Array<Wall>>
        private lateinit var wallTexture: Texture
        private lateinit var explosiveBarrelTexture: Texture
        private lateinit var walls: Array<Wall>
        var playerOnScoreboardTable: ConcurrentHashMap<String, Agent> = ConcurrentHashMap<String, Agent>()

        fun connectionSocket(ipAddress: String) {
            try {
                socket = IO.socket("http://$ipAddress:8080")
                socket.connect()
            } catch (e: Exception) {
                println("something went wrong!")
            }
        }

        fun getPlayer(): Player? {
            if (::player.isInitialized) {
                return player
            }
            return null
        }

        fun configSocketEvents(projectileTexture: Texture, pistolTexture: Texture, machineGunTexture: Texture,
                               shotgunTexture: Texture, bazookaTexture: Texture, playerTextures: TextureAtlas,
                               healthBarTexture: Texture, bazookaExplosionTextureAtlas: TextureAtlas,
                               wallMatrix: HashMap<String, Array<Wall>>, wallTexture: Texture,
                               explosiveBarrelTexture: Texture, walls: Array<Wall>) {

            this.projectileTexture = projectileTexture
            this.pistolTexture = pistolTexture
            this.machineGunTexture = machineGunTexture
            this.shotgunTexture = shotgunTexture
            this.bazookaTexture = bazookaTexture

            this.playerTextures = playerTextures
            this.healthBarTexture = healthBarTexture
            this.bazookaExplosionTextureAtlas = bazookaExplosionTextureAtlas
            this.wallMatrix = wallMatrix
            this.wallTexture = wallTexture
            this.explosiveBarrelTexture = explosiveBarrelTexture
            this.walls = walls

            socket.on(Socket.EVENT_CONNECT) {
                Gdx.app.log("SocketIO", "Connected")
            }
                    .on("socketID") { data ->
                        val obj: JSONObject = data[0] as JSONObject
                        val playerId = obj.getString("id")

                        createPlayer(playerId, healthBarTexture, playerTextures)

                        Gdx.app.log("SocketIO", "My ID: $playerId")
                    }
                    .on("playerDisconnected") { data ->
                        removeOpponent(data)
                    }
                    .on("newProjectile") { processNewProjectile(it) }
                    .on("agentData") { processAgentData(it) }
                    .on("projectileData") { processProjectileData(it) }
                    .on("pickupData") { processPickupData(it) }
                    .on("barrelData") { processBarrelData(it) }
                    .on("wallData") { processWallData(it) }
                    .on("newExplosion") { processNewExplosion(it) }
                    .on("scoreboardData") { processScoreboardData(it) }
                    .on("killConfirm") { processKillConfirm(it) }
        }

        var shouldPlayDeathSound = false;

        private fun processKillConfirm(data: kotlin.Array<Any>) {
            shouldPlayDeathSound = true;
        }

        private fun processScoreboardData(data: kotlin.Array<Any>) {
            val obj = data[0] as JSONObject
            val agents = obj.getJSONArray("scoreboardData")
            for (i in 0 until agents.length()) {
                val agent = agents[i] as JSONObject
                val id = agent.getString("id")
                val kills = agent.getInt("kills")
                val deaths = agent.getInt("deaths")
                val name = agent.getString("name")

                for (player in playerOnScoreboardTable.values) {
                    if (player.id == id) {
                        player.kills = kills
                        player.deaths = deaths
                    } else {
                        playerOnScoreboardTable[id] = Opponent(0f, 0f, name, kills, deaths, false,
                                0f, false, 0f, 0f, playerTextures, id,
                                healthBarTexture)
                    }
                }
            }
        }

        private fun processWallData(data: kotlin.Array<Any>) {
            this.walls.clear()
            generateEdgeWalls()
            val walls = data[0] as JSONArray
            for (i in 0 until walls.length()) {
                val obj = walls[i] as JSONObject
                val x = obj.getDouble("x").toFloat()
                val y = obj.getDouble("y").toFloat()
                val wall = Wall(x, y, wallTexture)
                for (zone in getZonesForRectangle(wall.bounds)) {
                    wallMatrix[zone]?.add(wall)
                }
                this.walls.add(wall)
            }
        }

        private fun processPickupData(data: kotlin.Array<Any>) {
            val obj = data[0] as JSONObject
            val picks = obj.getJSONArray("pickupData")

            for (pickup in pickups.values) {
                when (pickup) {
                    is PistolPickup -> pistolPickupPool.free(pickup)
                    is MachineGunPickup -> machineGunPickupPool.free(pickup)
                    is ShotgunPickup -> shotgunPickupPool.free(pickup)
                    is BazookaPickup -> bazookaPickupPool.free(pickup)
                }
            }

            pickups.clear()

            for (i in 0 until picks.length()) {
                val pickup = picks[i] as JSONObject
                val id = pickup.getString("id")
                val x = pickup.getDouble("x").toFloat()
                val y = pickup.getDouble("y").toFloat()
                val type = pickup.getString("type")

                pickups[id] = when (type) {
                    ProjectileType.PISTOL -> pistolPickupPool.obtain()
                    ProjectileType.SHOTGUN -> shotgunPickupPool.obtain()
                    ProjectileType.BAZOOKA -> bazookaPickupPool.obtain()
                    else -> machineGunPickupPool.obtain()
                }.apply { setPosition(x, y) }
            }
        }

        private fun processProjectileData(data: kotlin.Array<Any>) {
            val obj = data[0] as JSONObject
            val proj = obj.getJSONArray("projectileData")

            for (i in 0 until proj.length()) {
                val projectile = proj[i] as JSONObject
                val type = projectile.getString("type")
                val id = projectile.getString("id")
                val x = projectile.getLong("x").toFloat()
                val y = projectile.getLong("y").toFloat()
                val xSpeed = projectile.getDouble("xSpeed").toFloat()
                val ySpeed = projectile.getDouble("ySpeed").toFloat()
                val agentId = projectile.getString("agentId")

                if (projectiles[id] == null) {
                    projectiles[id] = when (type) {
                        ProjectileType.PISTOL -> pistolProjectilePool.obtain()
                        ProjectileType.SHOTGUN -> shotgunProjectilePool.obtain()
                        ProjectileType.BAZOOKA -> bazookaProjectilePool.obtain()
                        else -> machineGunProjectilePool.obtain()
                    }.apply {
                        setPosition(x, y)
                        velocity.x = xSpeed
                        velocity.y = ySpeed
                        this.agentId = agentId
                    }
                } else {
                    projectiles[id]?.apply {
                        setPosition(x, y)
                        velocity.x = xSpeed
                        velocity.y = ySpeed
                        this.agentId = agentId
                    }
                }
            }
        }

        private fun processBarrelData(data: kotlin.Array<Any>) {
            for (barrel in explosiveBarrels.values) explosiveBarrelPool.free(barrel)
            explosiveBarrels.clear()
            val barrels = data[0] as JSONArray

            for (i in 0 until barrels.length()) {
                val barrel = barrels[i] as JSONObject
                val x = barrel.getDouble("x").toFloat()
                val y = barrel.getDouble("y").toFloat()
                val id = barrel.getString("id")
                explosiveBarrels[id] = explosiveBarrelPool.obtain().apply { setPosition(x, y) }
            }
        }

        private fun processAgentData(data: kotlin.Array<Any>) {
            val obj = data[0] as JSONObject
            val agents = obj.getJSONArray("agentData")

            for (i in 0 until agents.length()) {
                val agent = agents[i] as JSONObject
                val id = agent.getString("id")
                val name = agent.getString("name")
                val isDead = agent.getBoolean("isDead")
                val currentHealth = agent.getLong("currentHealth").toFloat()
                val x = agent.getLong("x").toFloat()
                val y = agent.getLong("y").toFloat()
                val weapon = agent.getString("weapon")
                val xVelocity = agent.getLong("xVelocity").toFloat()
                val yVelocity = agent.getLong("yVelocity").toFloat()
                val angle = agent.getDouble("angle").toFloat()
                val invisible = agent.getBoolean("inv")
                if (id == player.id) {
                    if (!isDead) {
                        if (player.weapon.type != weapon) {
                            when (weapon) {
                                ProjectileType.PISTOL -> player.weapon = Pistol()
                                ProjectileType.MACHINE_GUN -> player.weapon = MachineGun()
                                ProjectileType.SHOTGUN -> player.weapon = Shotgun()
                                ProjectileType.BAZOOKA -> player.weapon = Bazooka()
                            }
                        }
                        val bulletsLeft = agent.getInt("bulletsLeft")
                        if (bulletsLeft == -1 && player.weapon.bulletsInChamber != -1) shouldPlayReload = true
                        //playerOnScoreboardTable[id]!!.name = name
                        player.apply {
                            this.invisible = invisible
                            this.name = name
                            this.isDead = isDead
                            setPosition(x, y)
                            this.weapon.bulletsInChamber = bulletsLeft
                            setPosition(x, y)
                            gotShot = player.currentHealth != currentHealth
                            this.currentHealth = currentHealth
                            setHealthBar(currentHealth, x, y)
                        }
                    } else player.isDead = true
                } else {
                    if (opponents[id] == null) {
                        createOpponent(id, x, y, name, currentHealth, playerTextures, healthBarTexture).apply {
                            velocity.x = xVelocity
                            setAngle(angle)
                            velocity.y = yVelocity
                            isMoving = xVelocity == 0f && yVelocity == 0f
                            this.invisible = invisible
                        }
                    } else {
                        opponents[id]?.apply {
                            this.name = name
                            gotShot = opponents[id]?.currentHealth != currentHealth
                            setPosition(x, y)
                            setAngle(angle)
                            velocity.x = xVelocity
                            velocity.y = yVelocity
                            setHealthBar(currentHealth, x, y)
                            this.isDead = isDead
                            this.currentHealth = currentHealth
                            healthBarSprite.setSize(currentHealth, HEALTH_BAR_SPRITE_HEIGHT)
                            isMoving = xVelocity == 0f && yVelocity == 0f
                            this.invisible = invisible
                        }
                    }
                }
            }
        }

        private fun processNewExplosion(data: kotlin.Array<Any>) {
            val explosion = data[0] as JSONObject
            val x = explosion.getDouble("x").toFloat()
            val y = explosion.getDouble("y").toFloat()
            val type = explosion.getString("type")
            if (type == ExplosionType.BAZOOKA) {
                bazookaExplosions.add(bazookaExplosionPool.obtain().apply {
                    this.justSpawned = true
                    this.x = x
                    this.y = y
                    resetTimer()
                })
            } else if (type == ExplosionType.BARREL) {
                barrelExplosions.add(barrelExplosionPool.obtain().apply {
                    this.justSpawned = true
                    this.x = x
                    this.y = y
                    resetTimer()
                })
            }
        }

        private fun processNewProjectile(data: kotlin.Array<Any>) {
            val projectile = data[0] as JSONObject
            val type = projectile.getString("type")
            val id = projectile.getString("id")
            val x = projectile.getLong("x").toFloat()
            val y = projectile.getLong("y").toFloat()
            val xSpeed = projectile.getDouble("xSpeed").toFloat()
            val ySpeed = projectile.getDouble("ySpeed").toFloat()
            val agentId = projectile.getString("agentId")

            if (agentId == player.id) player.weapon.shoot()

            if (projectiles[id] == null) {
                projectiles[id] = when (type) {
                    ProjectileType.PISTOL -> pistolProjectilePool.obtain()
                    ProjectileType.SHOTGUN -> shotgunProjectilePool.obtain()
                    ProjectileType.BAZOOKA -> {
                        bazookaProjectilePool.obtain()
                    }
                    else -> machineGunProjectilePool.obtain()
                }.apply {
                    setPosition(x, y)
                    velocity.x = xSpeed
                    velocity.y = ySpeed
                    justFired = true
                    this.agentId = agentId
                }
            } else {
                projectiles[id]?.apply {
                    setPosition(x, y)
                    velocity.x = xSpeed
                    velocity.y = ySpeed
                    justFired = true
                    this.agentId = agentId
                }
            }
        }

        fun setName(name: String) {
            val data = JSONObject()
            data.put("name", name)
            socket.emit("playerName", data)
        }

        private fun createOpponent(id: String, x: Float, y: Float, name: String, currentHealth: Float,
                                   playerTextures: TextureAtlas,
                                   healthBarTexture: Texture): Opponent {
            val opponent = Opponent(x, y, name, 0, 0, false, currentHealth, false, 0f, 0f,
                    playerTextures, id, healthBarTexture)
            opponents[id] = opponent
            return opponent
        }

        private fun removeOpponent(data: kotlin.Array<Any>) {
            val obj: JSONObject = data[0] as JSONObject
            val playerId = obj.getString("id")
            opponents.remove(playerId)
            playerOnScoreboardTable.remove(playerId)
        }

        private fun createPlayer(playerId: String, healthBarTexture: Texture, playerTextures: TextureAtlas) {
            val player = Player(500f, 500f, "", 0, 0, false,
                    PLAYER_MAX_HEALTH, false, playerTextures, healthBarTexture, playerId)
            this.player = player
            playerOnScoreboardTable[playerId] = player
        }

        fun startKey(keyLetter: String, b: Boolean) {
            val data = JSONObject()
            data.put(keyLetter, b)
            socket.emit("startKey", data)
        }

        fun stopKey(key: String, b: Boolean) {
            val data = JSONObject()
            data.put(key, true)
            socket.emit("stopKey", data)
        }

        fun restart() {
            socket.emit("restart")
        }

        fun playerRotation(s: String, facingDirectionAngle: Float) {
            val data = JSONObject()
            data.put(s, player.facingDirectionAngle)
            socket.emit("playerRotation", data)
        }

        fun mouseStart() {
            val data = JSONObject()
            data.put("Mouse", true)
            socket.emit("mouseStart", data)
        }

        fun mouseStop() {
            val data = JSONObject()
            data.put("Mouse", true)
            socket.emit("mouseStop", data)
        }

        fun pickWeapon() {
            socket.emit("pickWeapon")
        }

        private fun generateEdgeWalls() {
            for (i in 0 until MAP_HEIGHT step WALL_SPRITE_HEIGHT.toInt()) {
                walls.add(Wall(0f, i.toFloat(), wallTexture))
                walls.add(Wall(MAP_WIDTH - WALL_SPRITE_WIDTH, i.toFloat(), wallTexture))
            }
            for (i in WALL_SPRITE_WIDTH.toInt() until MAP_WIDTH - WALL_SPRITE_WIDTH.toInt() step WALL_SPRITE_WIDTH.toInt()) {
                walls.add(Wall(i.toFloat(), 0f, wallTexture))
                walls.add(Wall(i.toFloat(), MAP_HEIGHT - WALL_SPRITE_HEIGHT, wallTexture))
            }
        }
    }
}