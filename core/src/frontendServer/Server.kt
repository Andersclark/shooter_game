package frontendServer

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.utils.Array
import com.mygdx.game.model.*
import com.mygdx.game.settings.HEALTH_BAR_SPRITE_HEIGHT
import com.mygdx.game.settings.PLAYER_MAX_HEALTH
//import com.sun.awt.SecurityWarning.setPosition
import io.socket.client.IO
import io.socket.client.Socket
import ktx.assets.pool
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class Server {
    companion object {
        private lateinit var socket: Socket
        val pickups = ConcurrentHashMap<String, Pickup>()

        lateinit var projectileTexture: Texture
        lateinit var pistolTexture: Texture
        lateinit var machineGunTexture: Texture
        var shouldPlayReload = false

        lateinit private var player: Player
        val projectiles = ConcurrentHashMap<String, Projectile>()
        val pistolProjectilePool = pool { PistolProjectile(texture = projectileTexture) }
        var machineGunProjectilePool = pool { MachineGunProjectile(texture = projectileTexture) }
        var pistolPickupPool = pool { PistolPickup(texture = pistolTexture) }
        var machineGunPickupPool = pool { MachineGunPickup(texture = machineGunTexture) }
        val opponents = ConcurrentHashMap<String, Opponent>()

        fun connectionSocket() {
            try {
                socket = IO.socket("http://localhost:8080");
                socket.connect();
            } catch (e: Exception) {
            }
        }

        fun getPlayer(): Player? {
            if (::player.isInitialized) {return player}
            return null
        }

        fun configSocketEvents(projectileTexture: Texture, pistolTexture: Texture, machineGunTexture: Texture,
                               playerTextures: Array<Texture>, healthBarTexture: Texture) {

            this.projectileTexture = projectileTexture
            this.pistolTexture = pistolTexture
            this.machineGunTexture = machineGunTexture

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
            .on("gameData") { data ->
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
                    if (id == player.id) {
                        if (!isDead) {
                            //println("$x, $y")
                            player.isDead = isDead
                            player.setPosition(x, y)
                            if (player.weapon.type != weapon) {
                                when (weapon) {
                                    ProjectileType.PISTOL -> player.weapon = Pistol()
                                    ProjectileType.MACHINE_GUN -> player.weapon = MachineGun()
                                }
                            }
                            val bulletsLeft = agent.getInt("bulletsLeft")
                            if (bulletsLeft == -1 && player.weapon.bulletsInChamber != -1) shouldPlayReload = true
                            player.weapon.bulletsInChamber = bulletsLeft
                            player.setPosition(x, y)
                            player.currentHealth = currentHealth
                            player.setHealthBar(currentHealth, x, y)
                        } else player.isDead = true
                    } else {
                        if (opponents[id] == null) {
                            createOpponent(id, x, y, name, currentHealth, playerTextures, healthBarTexture)
                            opponents[id]?.velocity?.x = xVelocity
                            opponents[id]?.setAngle(angle)
                            opponents[id]?.velocity?.y = yVelocity
                        } else {
                            opponents[id]?.setPosition(x, y)
                            opponents[id]?.setAngle(angle)
                            opponents[id]?.velocity?.x = xVelocity
                            opponents[id]?.velocity?.y = yVelocity
                            opponents[id]?.setHealthBar(currentHealth, x, y)
                            opponents[id]?.isDead = isDead
                            opponents[id]?.currentHealth = currentHealth
                            opponents[id]?.healthBarSprite!!.setSize(currentHealth, HEALTH_BAR_SPRITE_HEIGHT)
                        }
                    }
                }

                val proj = obj.getJSONArray("projectileData")

                for (i in 0 until proj.length()) {
                    val projectile = proj[i] as JSONObject
                    val type = projectile.getString("type")
                    val id = projectile.getString("id")
                    val x = projectile.getLong("x").toFloat()
                    val y = projectile.getLong("y").toFloat()
                    val xSpeed = projectile.getDouble("xSpeed").toFloat()
                    val ySpeed = projectile.getDouble("ySpeed").toFloat()

                    if (projectiles[id] == null) {
                        projectiles[id] = when (type) {
                            ProjectileType.PISTOL -> pistolProjectilePool.obtain()
                            else -> machineGunProjectilePool.obtain()
                        }.apply {
                            setPosition(x, y)
                            velocity.x = xSpeed
                            velocity.y = ySpeed
                        }
                    } else {
                        projectiles[id]?.apply {
                            setPosition(x, y)
                            velocity.x = xSpeed
                            velocity.y = ySpeed
                        }
                    }
                }

                val picks = obj.getJSONArray("pickupData")

                for (pickup in pickups.values) {
                    if (pickup is PistolPickup) pistolPickupPool.free(pickup)
                    if (pickup is MachineGunPickup) machineGunPickupPool.free(pickup)
                }

                pickups.clear()

                for (i in 0 until picks.length()) {
                    val pickup = picks[i] as JSONObject
                    val id = pickup.getString("id")
                    val x = pickup.getDouble("x").toFloat()
                    val y = pickup.getDouble("y").toFloat()
                    val type = pickup.getString("type")

                    pickups[id] = when (type) {
                        ProjectileType.PISTOL -> pistolPickupPool.obtain().apply { setPosition(x, y) }
                        else -> machineGunPickupPool.obtain().apply { setPosition(x, y) }
                    }
                }
            }
            .on("newProjectile") { data ->
                val projectile = data[0] as JSONObject
                val type = projectile.getString("type")
                val id = projectile.getString("id")
                val x = projectile.getLong("x").toFloat()
                val y = projectile.getLong("y").toFloat()
                val xSpeed = projectile.getDouble("xSpeed").toFloat()
                val ySpeed = projectile.getDouble("ySpeed").toFloat()

                if (projectiles[id] == null) {
                    projectiles[id] = when (type) {
                        ProjectileType.PISTOL -> pistolProjectilePool.obtain()
                        else -> machineGunProjectilePool.obtain()
                    }.apply {
                        setPosition(x, y)
                        velocity.x = xSpeed
                        velocity.y = ySpeed
                        justFired = true
                    }
                } else {
                    projectiles[id]?.apply {
                        setPosition(x, y)
                        velocity.x = xSpeed
                        velocity.y = ySpeed
                        justFired = true
                    }
                }
            }
        }

        private fun createOpponent(id: String, x: Float, y: Float, name: String, currentHealth: Float,
                                   playerTextures: Array<Texture>,
                                   healthBarTexture: Texture) {
            opponents[id] = Opponent(x, y, name, false, currentHealth,0f, 0f,
                    playerTextures, id, healthBarTexture)
        }

        private fun removeOpponent(data: kotlin.Array<Any>) {
            val obj: JSONObject = data[0] as JSONObject
            val playerId = obj.getString("id")
            opponents.remove(playerId)
        }

        private fun createPlayer(playerId: String, healthBarTexture: Texture, playerTextures: Array<Texture>) {
            player = Player(500f, 500f, "Rami",false,
                    PLAYER_MAX_HEALTH, playerTextures, healthBarTexture, playerId)
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
    }
}