import { forwardRef, useEffect, useImperativeHandle, useRef } from "react";
import { motionConfig } from "./motionConfig";

const COLORS = ["rgba(255,23,68,.878)", "rgba(233,30,99,.82)", "rgba(255,69,105,.78)", "rgba(255,128,171,.74)"];

export interface PetTapHeartCanvasHandle {
  trigger(x: number, y: number): void;
}

interface Particle {
  startOffsetX: number;
  velocityX: number;
  velocityY: number;
  size: number;
  rotation: number;
  colorIndex: number;
}

interface Burst {
  id: number;
  x: number;
  y: number;
  startedAt: number;
  exitingAt: number | null;
  particles: Particle[];
}

class XorWowRandom {
  private x: number;
  private y: number;
  private z = 0;
  private w = 0;
  private v: number;
  private addend: number;

  constructor(seed: number) {
    this.x = seed | 0;
    this.y = 0;
    this.v = ~this.x;
    this.addend = (this.x << 10) ^ (this.y >>> 4);
    for (let index = 0; index < 64; index += 1) this.nextInt();
  }

  nextInt(): number {
    let t = this.x;
    t ^= t >>> 2;
    this.x = this.y;
    this.y = this.z;
    this.z = this.w;
    this.w = this.v;
    this.v = (this.v ^ (this.v << 4)) ^ (t ^ (t << 1));
    this.addend = (this.addend + 362_437) | 0;
    return (this.v + this.addend) | 0;
  }

  nextFloat(): number {
    return (this.nextInt() >>> 8) / 16_777_216;
  }

  nextIntBounded(bound: number): number {
    if ((bound & -bound) === bound) {
      return Math.floor(bound * ((this.nextInt() >>> 1) / 2_147_483_648));
    }
    return Math.floor(this.nextFloat() * bound);
  }
}

export function petTapHeartParticles(burstId: number): Particle[] {
  const heartConfig = motionConfig.petTap.hearts;
  const random = new XorWowRandom((
    burstId * heartConfig.seedMultiplier + heartConfig.seedOffset
  ) | 0);
  return Array.from({ length: heartConfig.particleCount }, () => {
    const angle = (-45 - random.nextFloat() * 90) / 180 * Math.PI;
    const speed = 11.2 + random.nextFloat() * 9.6;
    return {
      startOffsetX: -10 + random.nextFloat() * 20,
      velocityX: Math.cos(angle) * speed,
      velocityY: Math.sin(angle) * speed,
      size: 19.8 + random.nextFloat() * 19.8,
      rotation: -20 + random.nextFloat() * 40,
      colorIndex: random.nextIntBounded(4),
    };
  });
}

function drawHeart(
  context: CanvasRenderingContext2D,
  x: number,
  y: number,
  size: number,
  rotation: number,
  color: string,
  alpha: number,
) {
  const half = size / 2;
  context.save();
  context.translate(x, y);
  context.rotate(rotation / 180 * Math.PI);
  context.globalAlpha = alpha;
  context.fillStyle = color;
  context.beginPath();
  context.moveTo(0, half * .78);
  context.bezierCurveTo(-half * 1.12, half * .12, -half * .92, -half * .74, -half * .38, -half * .74);
  context.bezierCurveTo(-half * .08, -half * .74, 0, -half * .48, 0, -half * .31);
  context.bezierCurveTo(0, -half * .48, half * .08, -half * .74, half * .38, -half * .74);
  context.bezierCurveTo(half * .92, -half * .74, half * 1.12, half * .12, 0, half * .78);
  context.closePath();
  context.fill();
  context.restore();
}

export const PetTapHeartCanvas = forwardRef<PetTapHeartCanvasHandle>(function PetTapHeartCanvas(_, ref) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const burstsRef = useRef<Burst[]>([]);
  const frameRef = useRef<number | null>(null);
  const nextIdRef = useRef(0);
  const lastBurstAtRef = useRef(-Infinity);

  const draw = (now: number) => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ratio = Math.min(window.devicePixelRatio || 1, 2);
    if (canvas.width !== Math.round(402 * ratio) || canvas.height !== Math.round(874 * ratio)) {
      canvas.width = Math.round(402 * ratio);
      canvas.height = Math.round(874 * ratio);
    }
    const context = canvas.getContext("2d");
    if (!context) return;
    context.setTransform(ratio, 0, 0, ratio, 0, 0);
    context.clearRect(0, 0, 402, 874);
    burstsRef.current = burstsRef.current.filter((burst) => {
      const elapsed = now - burst.startedAt;
      const exitElapsed = burst.exitingAt === null ? 0 : now - burst.exitingAt;
      return elapsed < motionConfig.petTap.hearts.lifetimeMillis && (
        burst.exitingAt === null || exitElapsed < motionConfig.petTap.hearts.fadeMillis
      );
    });
    for (const burst of burstsRef.current) {
      const fraction = Math.max(
        0,
        Math.min(1, (now - burst.startedAt) / motionConfig.petTap.hearts.lifetimeMillis),
      );
      const elapsedFrames = Math.floor(fraction * (136 / 1.2));
      const exitAlpha = burst.exitingAt === null
        ? 1
        : Math.max(0, 1 - (now - burst.exitingAt) / motionConfig.petTap.hearts.fadeMillis);
      for (const particle of burst.particles) {
        let x = burst.x + particle.startOffsetX;
        let y = burst.y;
        let velocityX = particle.velocityX;
        let velocityY = particle.velocityY;
        for (let frame = 0; frame < elapsedFrames; frame += 1) {
          x += velocityX;
          y += velocityY;
          velocityY -= .04;
          velocityX *= .99;
          velocityY *= .99;
        }
        const remainingLife = Math.max(0, 136 - elapsedFrames * 1.2);
        const opacity = Math.max(0, Math.min(1, remainingLife / 100)) * exitAlpha;
        const pulse = 1 + Math.sin(remainingLife * .2) * .1;
        drawHeart(context, x, y, particle.size * pulse * 1.1, particle.rotation, COLORS[particle.colorIndex], opacity * .2);
        drawHeart(context, x, y, particle.size * pulse, particle.rotation, COLORS[particle.colorIndex], opacity);
      }
    }
    if (burstsRef.current.length > 0) {
      frameRef.current = window.requestAnimationFrame(draw);
    } else {
      frameRef.current = null;
    }
  };

  useImperativeHandle(ref, () => ({
    trigger(x, y) {
      const now = performance.now();
      if (now - lastBurstAtRef.current < motionConfig.petTap.hearts.burstIntervalMillis) return;
      lastBurstAtRef.current = now;
      const active = burstsRef.current.filter((burst) => burst.exitingAt === null);
      if (active.length >= motionConfig.petTap.hearts.maxActiveBursts) {
        active[0].exitingAt = now;
      }
      nextIdRef.current += 1;
      burstsRef.current.push({
        id: nextIdRef.current,
        x,
        y,
        startedAt: now,
        exitingAt: null,
        particles: petTapHeartParticles(nextIdRef.current),
      });
      if (frameRef.current === null) frameRef.current = window.requestAnimationFrame(draw);
    },
  }), []);

  useEffect(() => () => {
    if (frameRef.current !== null) window.cancelAnimationFrame(frameRef.current);
    burstsRef.current = [];
  }, []);

  return <canvas ref={canvasRef} className="pet-tap-hearts" aria-hidden="true" />;
});
