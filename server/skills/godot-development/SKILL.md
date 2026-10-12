---
name: godot-development
description: Godot projects and GDScript: scenes, nodes, signals, scripts, cameras, controllers, UI, debugging, Godot 3 vs 4.
triggers: godot, gdscript, .gd, .tscn, .tres, project.godot, scene, node2d, node3d, characterbody, signal, autoload, shader, camera3d, camera2d, tween, animationplayer
tools: workspace, artifacts, local_files, local_git, claude_code
---
Working on Godot for Glen:

First establish the engine version; never assume it.
- Open the project in a workspace; workspace_open/open_folder_as_workspace report `godot` with engine_version and generation.
- project.godot `config_version=5` and `config/features=PackedStringArray("4.x", ...)` = Godot 4. `config_version=4` = Godot 3.
- Write syntax for that generation only.

Godot 4 (GDScript 2.0) essentials:
- `extends CharacterBody3D`; movement via the built-in `velocity` then `move_and_slide()` (no arguments).
- Annotations: `@export var speed := 5.0`, `@onready var cam: Camera3D = $Camera3D`, `@tool`.
- Signals: `signal hit(damage: int)`; connect with `button.pressed.connect(_on_pressed)`; emit with `hit.emit(3)`.
- `await get_tree().create_timer(1.0).timeout` (not `yield`). `Tween`: `var t := create_tween()`.
- Renamed from 3.x: KinematicBody→CharacterBody, Spatial→Node3D, `rand_range`→`randf_range`, `instance()`→`instantiate()`,
  `PoolStringArray`→`PackedStringArray`, `onready`→`@onready`, `export`→`@export`, `deg2rad`→`deg_to_rad`.
- Input: define actions in Project Settings → Input Map; `Input.get_vector("left","right","forward","back")`.
- Physics in `_physics_process(delta)`, visuals/UI in `_process(delta)`. Camera smoothing: lerp in _process with `delta`.
- Typed GDScript (`var x: int`, `-> void`) catches errors earlier; use it in new code.

Godot 3 essentials (only if the project is 3.x): `KinematicBody`, `move_and_slide(velocity, Vector3.UP)` returns the new velocity,
`export var`, `onready var`, `yield(get_tree().create_timer(1), "timeout")`, `connect("pressed", self, "_on_pressed")`.

Investigating a bug (e.g. a camera problem):
1. Find the camera and its script (workspace_search for Camera3D/Camera2D, `current`, `make_current`, `look_at`, SpringArm).
2. Check which node is current, the node path the script expects (`$Path` / `get_node`), and whether the scene tree matches (read the .tscn).
3. Check process mode and order (camera following in _process vs the player in _physics_process causes jitter; use
   physics interpolation or follow in _physics_process).
4. Validate: workspace_run godot_check (loads the project headless and reports script/scene errors) or gdscript_check for one file.
   If Godot isn't installed on Vision, say so; don't claim validation.
5. Show the diff; offer Claude Code for an independent review.

Scenes (.tscn) are text: edit them carefully, keep `[ext_resource]` ids and `load_steps` consistent, and prefer changing scripts.
