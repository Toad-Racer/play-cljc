(ns play-cljc.core
  (:require [iglu.core :as ig]
            [iglu.parse :as parse]
            [play-cljc.utils :as u]))

(defprotocol Renderable
  (render [this]))

(defrecord Entity
  [gl vertex fragment
   vertex-source fragment-source
   program vao
   uniform-locations texture-locations
   index-count])

(defn glsl-type->platform-type [glsl-type]
  (case glsl-type
    (vec2 vec3 vec4)    js/Float32Array
    (dvec2 dvec3 dvec4) js/Float64Array
    (ivec2 ivec3 ivec4) js/Int32Array
    (uvec2 uvec3 uvec4) js/Uint32Array))

(defn init-texture [gl m uni-loc {:keys [data params opts mipmap alignment]}]
  (let [unit (count (:texture-locations m))
        texture (.createTexture gl)]
    (.activeTexture gl (+ gl.TEXTURE0 unit))
    (.bindTexture gl gl.TEXTURE_2D texture)
    (doseq [[param-name param-val] params]
      (.texParameteri gl gl.TEXTURE_2D param-name param-val))
    (when alignment
      (.pixelStorei gl gl.UNPACK_ALIGNMENT alignment))
    (let [{:keys [mip-level internal-fmt src-fmt src-type width height border]} opts]
      (if (and width height border)
        (.texImage2D gl gl.TEXTURE_2D mip-level internal-fmt width height border src-fmt src-type data)
        (.texImage2D gl gl.TEXTURE_2D mip-level internal-fmt src-fmt src-type data)))
    (when mipmap
      (.generateMipmap gl gl.TEXTURE_2D))
    (update m :texture-locations conj uni-loc)))

(defn call-uniform* [gl m glsl-type uni-loc data]
  (case glsl-type
    vec2 (.uniform2fv gl uni-loc data)
    vec3 (.uniform3fv gl uni-loc data)
    vec4 (.uniform4fv gl uni-loc data)
    mat2 (.uniformMatrix2fv gl uni-loc false data)
    mat3 (.uniformMatrix3fv gl uni-loc false data)
    mat4 (.uniformMatrix4fv gl uni-loc false data)
    sampler2D (init-texture gl m uni-loc data)))

(defn get-uniform-type [{:keys [vertex fragment]} uni-name]
  (or (get-in vertex [:uniforms uni-name])
      (get-in fragment [:uniforms uni-name])
      (parse/throw-error
        (str "You must define " uni-name
          " in your vertex or fragment shader"))))

(defn call-uniform [gl {:keys [uniform-locations] :as m} [uni-name uni-data]]
  (let [uni-type (get-uniform-type m uni-name)
        uni-loc (get uniform-locations uni-name)]
    (or (call-uniform* gl m uni-type uni-loc uni-data)
        m)))

(defn create-entity [{:keys [gl vertex fragment attributes uniforms] :as m}]
  (let [vertex-source (ig/iglu->glsl :vertex vertex)
        fragment-source (ig/iglu->glsl :fragment fragment)
        program (u/create-program gl vertex-source fragment-source)
        _ (.useProgram gl program)
        vao (.createVertexArray gl)
        _ (.bindVertexArray gl vao)
        counts (mapv (fn [[attr-name {:keys [data] :as opts}]]
                       (let [attr-type (or (get-in vertex [:attributes attr-name])
                                           (parse/throw-error
                                             (str "You must define " attr-name
                                               " in your vertex shader")))
                             attr-type (or (glsl-type->platform-type attr-type)
                                           (parse/throw-error
                                             (str "The type " attr-type
                                               " is invalid for attribute " attr-name)))]
                         (u/create-buffer gl program (name attr-name)
                           (if (js/ArrayBuffer.isView data)
                             data
                             (new attr-type data))
                           opts)))
                 attributes)
        uniform-locations (reduce
                            (fn [m uniform]
                              (assoc m uniform
                                (.getUniformLocation gl program (name uniform))))
                            {}
                            (-> #{}
                                (into (-> vertex :uniforms keys))
                                (into (-> fragment :uniforms keys))))
        entity (map->Entity {:gl gl
                             :vertex vertex
                             :fragment fragment
                             :vertex-source vertex-source
                             :fragment-source fragment-source
                             :program program
                             :vao vao
                             :uniform-locations uniform-locations
                             :texture-locations []
                             :index-count (apply max counts)})
        entity (reduce
                 (partial call-uniform gl)
                 entity
                 uniforms)]
    (.bindVertexArray gl nil)
    entity))

(extend-type Entity
  Renderable
  (render [{:keys [gl program vao index-count uniforms] :as entity}]
    (.useProgram gl program)
    (.bindVertexArray gl vao)
    (let [{:keys [textures]} (reduce
                               (partial call-uniform gl)
                               entity
                               uniforms)]
      (dotimes [i (range (count textures))]
        (.uniform1i gl (nth textures i) i)))
    (.drawArrays gl gl.TRIANGLES 0 index-count)
    (.bindVertexArray gl nil)))

(defrecord Clear [gl color depth stencil])

(extend-type Clear
  Renderable
  (render [{:keys [gl color depth stencil]}]
    (when-let [{:keys [r g b a]} color]
      (.clearColor gl r g b a))
    (some->> depth (.clearDepth gl))
    (some->> stencil (.clearStencil gl))
    (->> [(when color gl.COLOR_BUFFER_BIT)
          (when depth gl.DEPTH_BUFFER_BIT)
          (when stencil gl.STENCIL_BUFFER_BIT)]
         (remove nil?)
         (apply bit-or)
         (.clear gl))))

(defrecord Viewport [gl x y width height])

(extend-type Viewport
  Renderable
  (render [{:keys [gl x y width height]}]
    (.viewport gl x y width height)))

