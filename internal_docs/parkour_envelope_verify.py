import math
# ---- verified 1.21.11 constants (javap, mojang-mapped) ----
G   = 0.08          # Attributes.GRAVITY default
QV  = 0.98          # vertical drag (travelInAir: (vy - g) * 0.98)
QH  = 0.91          # air horizontal drag (f4 = friction*0.91, friction=1.0 airborne)
QG  = 0.6*0.91      # ground drag (block friction 0.6 * 0.91) = 0.546
V0Y = 0.42          # Attributes.JUMP_STRENGTH default (0.41999998688697815)
BOOST = 0.2         # sprint-jump horizontal impulse (jumpFromGround)
SPEED = 0.1*1.3     # player MOVEMENT_SPEED 0.1 * sprint modifier 1.3 = 0.13
INPUT = 0.98        # aiStep: xxa/zza *= 0.98
A_G = SPEED*(0.21600002/(0.6**3))*INPUT      # ground accel/tick
A_A = 0.025999999*INPUT                      # air accel/tick (getFlyingSpeed sprinting)
TAKEOFF_EDGE = 0.35; TAKEOFF_EDGE_ALONG = 0.40
S2 = math.sqrt(2.0)

# ---- closed forms ----
# vertical: vy_t = (V0Y + K)*QV^(t-1) - K,  K = -fixedpoint = G*QV/(1-QV) = 3.92
K = G*QV/(1-QV)
def y(T):   # feet height after T air ticks (closed form: geometric sum)
    return (V0Y+K)*(1-QV**T)/(1-QV) - K*T
def T_dy(dy):  # LAST tick with feet >= dy (the rising arc dips below +1 early then re-crosses,
               # so this must be a max over the whole arc, not a first-failure walk)
    return max(t for t in range(1, 30) if y(t) >= dy)
# horizontal: steady sprint v_inf = A_G/(1-QG); jump tick v1 = v_inf*QG + BOOST + A_G, ground drag on jump tick
V_INF = A_G/(1-QG)
V1 = V_INF*QG + BOOST + A_G
V2 = V1*QG + A_A                       # first pure-air tick
M  = A_A/(1-QH)                        # air terminal from input
def X(T):   # cumulative center travel after T ticks (tick 1 = jump tick)
    if T < 1: return 0.0
    return V1 + (T-1)*M + (V2-M)*(1-QH**(T-1))/(1-QH)

print(f"K={K}  apex tick=6  y(6)={y(6):.4f}")
print(f"A_G={A_G:.6f} V_INF={V_INF:.6f} (x20={V_INF*20:.3f} m/s)  V1={V1:.6f} V2={V2:.6f} M={M:.6f}")
for dy in (1,0,-1,-2,-3,-4):
    T=T_dy(dy); print(f"dy={dy:+d}: T={T:2d} y(T)={y(T):+.4f} y(T+1)={y(T+1):+.4f}  X(T)={X(T):.4f}")
# recurrence cross-check (same discrete system)
vy=V0Y; yy=0; vx=V1; xx=0; drag=QG
for t in range(1,19):
    yy+=vy; xx+=vx
    if t in (6,8,11,13,14,16):
        print(f"  recurrence t={t}: y={yy:.4f} x={xx:.4f}   (closed y={y(t):.4f} x={X(t):.4f})")
    vy=(vy-G)*QV; vx=vx*drag+A_A; drag=QH
# ---- envelope: Rule A (takeoff at executor trigger, full-sprint steady) ----
print("\ncardinal D_req(g)=g-0.15 ; diagonal D_req(g)=(g+0.2)*sqrt2-0.40")
for name,dy in (("flat",0),("rise+1",1),("fall-1",-1),("fall-2",-2),("fall-3",-3)):
    T=T_dy(dy); B=X(T)
    gmax = math.floor(B+0.15)
    gmax_d = math.floor((B+0.40)/S2 - 0.2)
    print(f"{name}: budget={B:.4f}  cardinal gmax={gmax} (margin at gmax: {B-(gmax-0.15):+.4f}; next fails by {(gmax+1-0.15)-B:.4f})  diag gmax={gmax_d} (req at gmax={((gmax_d+0.2)*S2-0.40):.4f}, next req={((gmax_d+1.2)*S2-0.40):.4f})")
# last-pixel physics (tick-perfect): D_req = g-0.6 / (g+0.2+? ) for reference
B=X(T_dy(0)); print(f"\nlast-pixel flat: g<= {B+0.6:.4f}  (flat4 needs 3.4 -> {'IN' if B>=3.4 else 'OUT by %.3f'%(3.4-B)})")
