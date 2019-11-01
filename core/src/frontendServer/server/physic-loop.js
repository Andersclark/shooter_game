const Matter = require('matter-js');
const shortid = require('shortid');

const Agent = require('./models/Agent');
const Pistol = require('./models/Pistol');
const PistolProjectile = require('./models/PistolProjectile');
const constants = require('./settings/constants');

const agents = [];
const projectiles = [];

const sleep = ms => new Promise((resolve => setTimeout(resolve, ms)));

let lastLoop;

async function physicLoop() {
    while (true) {
        const currentTime = new Date().getTime();
        delta = (currentTime - lastLoop) / 1000;
        lastLoop = currentTime;

        for (agent of agents) {
            checkControls(agent, delta)
        }
        calculateProjectilePositions(delta);

        await sleep(1000 / 60)
    }
}

function calculateProjectilePositions(delta) {
    for (projectile of projectiles) {
        projectile.bounds.position.x = projectile.bounds.position.x + projectile.velocity.x * delta * projectile.speed;
        projectile.bounds.position.y = projectile.bounds.position.y + projectile.velocity.y * delta * projectile.speed;

        if (projectile.bounds.position.x < 0 || projectile.bounds.position.x > constants.MAP_WIDTH ||
            projectile.bounds.position.y < 0 || projectile.bounds.position.y > constants.MAP_HEIGHT) {
            projectiles.splice(projectiles.indexOf(projectile), 1);
        }

        for (agent of agents) {
            if (Matter.SAT.collides(agent.bounds, projectile.bounds).collided) {
                projectiles.splice(projectiles.indexOf(projectile), 1);
            }
        }
    }
}

function moveAgent(agent, x, y) {
    agent.bounds.position.x = Matter.Common.clamp(x, constants.WALL_SPRITE_WIDTH,
        constants.MAP_WIDTH - constants.PLAYER_SPRITE_WIDTH - constants.WALL_SPRITE_WIDTH);
    agent.bounds.position.y = Matter.Common.clamp(y, constants.WALL_SPRITE_HEIGHT,
        constants.MAP_HEIGHT - constants.WALL_SPRITE_HEIGHT - constants.PLAYER_SPRITE_HEIGHT);
}

function spawnPistolProjectile(x, y, xSpeed, ySpeed) {
    projectiles.push(new PistolProjectile(x, y, xSpeed, ySpeed, shortid.generate()));
}

function checkControls(agent, delta) {
    if (agent.isLMPressed && agent.canShoot()) {
        agent.shoot();
        const xCentre = agent.bounds.position.x + constants.PLAYER_SPRITE_WIDTH / 2;
        const yCentre = agent.bounds.position.y + constants.PLAYER_SPRITE_HEIGHT / 2;

        const edgePoint = projectToRectEdge(agent.facingDirectionAngle, agent);

        edgePoint.x += xCentre - constants.PLAYER_SPRITE_WIDTH / 2;
        edgePoint.y += yCentre - constants.PLAYER_SPRITE_HEIGHT / 2;

        spawnPistolProjectile(edgePoint.x, edgePoint.y,
            Math.cos(Math.PI / 180 * agent.facingDirectionAngle),
            Math.sin(Math.PI / 180 * agent.facingDirectionAngle))
    }

    let movementSpeed = constants.PLAYER_MOVEMENT_SPEED;
    let pressedKeys = 0;

    if (agent.isWPressed) pressedKeys++;
    if (agent.isAPressed) pressedKeys++;
    if (agent.isSPressed) pressedKeys++;
    if (agent.isDPressed) pressedKeys++;

    if (pressedKeys > 1) movementSpeed *= 0.7;

    if (agent.isWPressed) moveAgent(agent, agent.bounds.position.x, agent.bounds.position.y + movementSpeed * delta);

    if (agent.isSPressed) moveAgent(agent, agent.bounds.position.x, agent.bounds.position.y - movementSpeed * delta);

    if (agent.isAPressed) moveAgent(agent, agent.bounds.position.x - movementSpeed * delta, agent.bounds.position.y);

    if (agent.isDPressed) moveAgent(agent, agent.bounds.position.x + movementSpeed * delta, agent.bounds.position.y)
}

function projectToRectEdge(angle, agent) {
    const twoPI = Math.PI * 2;
    let theta = angle * Math.PI / 180;

    while (theta < -Math.PI) theta += twoPI;
    while (theta > Math.PI) theta -= twoPI;

    const rectAtan = Math.atan2(constants.PLAYER_SPRITE_HEIGHT, constants.PLAYER_SPRITE_WIDTH);
    const tanTheta = Math.tan(theta);

    let region;

    if ((theta > -rectAtan) && (theta <= rectAtan)) region = 1;
    else if ((theta > rectAtan) && (theta <= (Math.PI - rectAtan))) region = 2;
    else if ((theta > (Math.PI - rectAtan)) || (theta <= -(Math.PI - rectAtan))) region = 3;
    else region = 4;

    const edgePoint = {
        x: constants.PLAYER_SPRITE_WIDTH / 2,
        y: constants.PLAYER_SPRITE_HEIGHT / 2
    };

    let xFactor = 1;
    let yFactor = 1;

    if (region === 3 || region === 4) {
        xFactor = -1;
        yFactor = -1;
    }

    if (region === 1 || region === 3) {
        edgePoint.x += xFactor * (constants.PLAYER_SPRITE_WIDTH / 2);
        edgePoint.y += yFactor * (constants.PLAYER_SPRITE_WIDTH / 2) * tanTheta;
    } else {
        edgePoint.x += xFactor * (constants.PLAYER_SPRITE_HEIGHT / (2 * tanTheta));
        edgePoint.y += yFactor * (constants.PLAYER_SPRITE_HEIGHT / 2);
    }

    return edgePoint;
}

module.exports = {physicLoop, agents, projectiles, lastLoop};

