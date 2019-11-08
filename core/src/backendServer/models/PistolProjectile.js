const matter = require('matter-js');
const Projectile = require('./Projectile');
const ProjectileType = require('./ProjectileType');
const constants = require('../settings/constants');

class PistolProjectile extends Projectile {
    constructor(x = 0, y = 0, xSpeed = 0, ySpeed = 0, id) {
        super(x, y, xSpeed, ySpeed, constants.STANDARD_PROJECTILE_WIDTH / 2, ProjectileType.PISTOL, constants.PISTOL_BULLET_SPEED, id)
    }
}

module.exports = PistolProjectile;