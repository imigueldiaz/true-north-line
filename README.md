# North Line

North Line  is a small Android application developed with the primary purpose of learning Kotlin and creating a tool to locate true north for aligning an equatorial mount. The app utilizes the device's camera and sensors to provide an accurate estimation of the true north direction.

**Note:** This project is a work in progress and is not yet ready for production use. The app is currently in the early stages of development, and additional features and improvements are planned for future releases.


## Features

- Utilizes the device's camera preview to display the surrounding environment
- Overlays a north line on the camera preview to indicate the direction of true north
- Calculates the azimuth and magnetic declination based on the device's orientation and location
- Adjusts the north line's position and color based on the calculated azimuth and declination
- Automatically selects the camera with the longest focal length (telephoto) for improved accuracy
- Calculates the camera's field of view (FOV) based on the sensor size and focal length
- Provides a user-friendly interface for easy navigation and interaction

## Getting Started

To run the True North Finder app on your Android device, follow these steps:

1. Clone the repository to your local machine using the following command:

```bash
git clone https://github.com/imigueldiaz/true-north-line.git
```
2. Open the project in Android Studio.

3. Connect your Android device to your computer via USB or WiFi.

4. Build and run the app on your connected device using Android Studio.

## How It Works

The True North Finder app leverages the device's camera and sensors to determine the direction of true north. Here's a brief overview of how the app functions:

1. The app requests camera and location permissions from the user.

2. It automatically selects the camera with the longest focal length (telephoto) for improved accuracy.

3. The camera preview is displayed on the screen, showing the surrounding environment.

4. The app calculates the azimuth (horizontal angle) based on the device's orientation using the accelerometer and magnetometer sensors.

5. It retrieves the device's location using GPS or network-based location services.

6. The magnetic declination is calculated based on the device's location and the current date.

7. A north line is overlaid on the camera preview, indicating the direction of true north.

8. The north line's position is adjusted based on the calculated azimuth and declination.

9. The color of the north line changes to green when the device is aligned with true north and blue otherwise.

10. The app continuously updates the north line's position and color as the device's orientation and location change.

## Code Structure

The True North Finder app is structured into the following main components:

- `MainActivity`: The entry point of the app, responsible for initializing the camera and sensors.
- `CustomCameraActivity`: Handles the camera preview, camera selection, and interaction with the `NorthLineView`.
- `NorthLineView`: A custom view that overlays the north line on the camera preview and handles the calculations for azimuth, declination, and field of view.
- `BaseActivity`: A base activity class that provides common functionality for sensor management and location updates.

## Dependencies

The True North Finder app relies on the following dependencies:

- Android Jetpack libraries for camera and sensor management
- Android KTX extensions for concise and idiomatic Kotlin code

## Future Enhancements

Some potential enhancements for the True North Finder app include:

- Adding a compass view to provide a more intuitive representation of the device's orientation
- Implementing a calibration feature to improve the accuracy of the sensor readings
- Providing a settings screen to allow users to customize the app's behavior and appearance
- Integrating with external hardware, such as a motorized equatorial mount, for automated alignment

## Contributing

Contributions to the True North Finder app are welcome! If you find any issues or have suggestions for improvements, please open an issue or submit a pull request on the GitHub repository.

## License

The True North Finder app is open-source software licensed under the [MIT License](LICENSE).
